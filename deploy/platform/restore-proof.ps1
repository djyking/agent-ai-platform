param(
  [Parameter(Mandatory=$true)][string]$DefaultsFile,
  [Parameter(Mandatory=$true)][string]$SourceDatabase,
  [Parameter(Mandatory=$true)][string]$PrivateDirectory,
  [Parameter(Mandatory=$true)][string]$Report,
  [string]$MySqlBin = 'C:/Program Files/MySQL/MySQL Server 8.0/bin'
)
$ErrorActionPreference='Stop'
if($SourceDatabase -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$'){throw 'Invalid source database'}
$taskOption=(Resolve-Path -LiteralPath $DefaultsFile).Path
$taskDirectory=[IO.Path]::GetFullPath($PrivateDirectory)
if(Test-Path -LiteralPath $taskDirectory){throw 'Use a new private proof directory'}
New-Item -ItemType Directory -Path $taskDirectory | Out-Null
$taskRestore='harness_restore_'+[guid]::NewGuid().ToString('N').Substring(0,16)
$taskDump=Join-Path $taskDirectory 'snapshot.private.sql'
$taskMysql=Join-Path $MySqlBin 'mysql.exe'
$taskDumpProgram=Join-Path $MySqlBin 'mysqldump.exe'
function Query([string]$Database,[string]$Sql){
  $value=& $taskMysql "--defaults-extra-file=$taskOption" '--batch' '--skip-column-names' '--raw' "--database=$Database" "--execute=$Sql"
  if($LASTEXITCODE -ne 0){throw 'MySQL proof query failed'}
  return ($value -join "`n")
}
function Sha([string]$Value){
  $bytes=[Text.Encoding]::UTF8.GetBytes($Value)
  return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}
# Compare exact rows through server-side JSON/text hashes without disclosing business payloads.
$taskQueries=[ordered]@{
  schema="SELECT component,schema_version FROM harness_schema ORDER BY component"
  runs="SELECT run_id,revision,fence,COALESCE(lease_until,0),run_status,COALESCE(next_attempt_at,0),event_sequence,SHA2(snapshot_json,256) FROM harness_runs ORDER BY run_id"
  events="SELECT run_id,event_sequence,SHA2(event_json,256) FROM harness_run_events ORDER BY run_id,event_sequence"
  owners="SELECT run_id,project_id,application_id,subject_id,delegation_id,SHA2(release_json,256),SHA2(limits_json,256),COALESCE(client_reference,''),created_at,reserved_tokens FROM platform_runs ORDER BY run_id"
  commands="SELECT scope_hash,request_digest,run_id,SHA2(response_json,256),accepted_at FROM platform_commands ORDER BY scope_hash"
  audit="SELECT command_hash,run_id,actor_scope,operation,SHA2(COALESCE(reason_text,''),256),accepted_at FROM platform_command_audit ORDER BY command_hash"
  evidence="SELECT evidence_id,project_id,run_id,invocation_id,invocation_digest,SHA2(result_json,256),verifier,verified_at FROM platform_evidence ORDER BY evidence_id"
  releases="SELECT project_id,release_id,digest,SHA2(manifest_json,256) FROM platform_releases ORDER BY project_id,release_id"
  scopes="SELECT scope_key FROM platform_scope_locks ORDER BY scope_key"
  slots="SELECT pool_id,slot_index,COALESCE(owner_id,''),COALESCE(lease_until,0) FROM platform_slots ORDER BY pool_id,slot_index"
}
$taskCatalogPresent=Query $SourceDatabase "SELECT COUNT(*) FROM harness_schema WHERE component='catalog'"
if($taskCatalogPresent -eq '1'){
  $taskQueries['catalogResources']="SELECT project_id,resource_type,resource_id,revision,SHA2(document_json,256) FROM catalog_resources ORDER BY project_id,resource_type,resource_id"
  $taskQueries['catalogVersions']="SELECT project_id,resource_type,resource_id,version_no,digest,SHA2(document_json,256),disabled FROM catalog_versions ORDER BY project_id,resource_type,resource_id,version_no"
  $taskQueries['catalogReleaseLinks']="SELECT project_id,release_id,agent_id,version_no,SHA2(refs_json,256) FROM catalog_release_links ORDER BY project_id,release_id"
  $taskQueries['catalogCommands']="SELECT command_hash,request_digest,SHA2(response_json,256),project_id,actor_scope,resource_type,resource_id,operation,accepted_at FROM catalog_commands ORDER BY command_hash"
}
$taskTracePresent=Query 'information_schema' "SELECT COUNT(*) FROM TABLES WHERE TABLE_SCHEMA='$SourceDatabase' AND TABLE_NAME='harness_platform_trace'"
if($taskTracePresent -eq '1'){
  $taskQueries['trace']="SELECT sequence_id,run_id,COALESCE(trace_id,''),COALESCE(invocation_id,''),COALESCE(attempt_id,''),COALESCE(node_id,''),operation_name,target_name,outcome,started_at,duration_ms FROM harness_platform_trace ORDER BY sequence_id"
}
$before=[ordered]@{}
foreach($entry in $taskQueries.GetEnumerator()){$before[$entry.Key]=Sha (Query $SourceDatabase $entry.Value)}
& $taskDumpProgram "--defaults-extra-file=$taskOption" '--single-transaction' '--skip-lock-tables' '--no-tablespaces' '--set-gtid-purged=OFF' '--hex-blob' '--default-character-set=utf8mb4' "--result-file=$taskDump" $SourceDatabase
if($LASTEXITCODE -ne 0){throw 'Backup failed; private partial file retained'}
$existing=Query 'information_schema' "SELECT COUNT(*) FROM SCHEMATA WHERE SCHEMA_NAME='$taskRestore'"
if($existing -ne '0'){throw 'Refusing to reuse a restore database'}
$null=Query 'information_schema' "CREATE DATABASE $taskRestore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
$null=Query $taskRestore ('source '+$taskDump.Replace('\','/'))
$checks=@()
foreach($entry in $taskQueries.GetEnumerator()){
  $sourceHash=Sha (Query $SourceDatabase $entry.Value)
  $restoredHash=Sha (Query $taskRestore $entry.Value)
  if($sourceHash -ne $before[$entry.Key]){throw "Source changed during proof: $($entry.Key); quiesce workers and use a new directory"}
  if($sourceHash -ne $restoredHash){throw "Restored rows differ: $($entry.Key)"}
  $checks+=@{tableGroup=$entry.Key;sha256=$sourceHash;exactMatch=$true}
}
$proof=@{schemaVersion=1;status='PASSED';at=[DateTime]::UtcNow.ToString('o');sourceDatabase=$SourceDatabase;restoredDatabase=$taskRestore;mysqlVersion=(Query $taskRestore 'SELECT VERSION()');backupSha256=(Get-FileHash -LiteralPath $taskDump -Algorithm SHA256).Hash.ToLowerInvariant();checks=$checks;restoredWorkersStarted=$false;sourceDatabaseOverwritten=$false;privateBackupRetained=$true;boundary='Logical backup restored into a fresh isolated schema; no external action replay, production RPO/RTO, or identity database restore claimed'}
$proof | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Report -Encoding utf8
@{status='PASSED';report=$Report;restoredDatabase=$taskRestore;tableGroups=$checks.Count} | ConvertTo-Json
