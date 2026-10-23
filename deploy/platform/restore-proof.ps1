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
  $value=& $taskMysql "--defaults-extra-file=$taskOption" '--default-character-set=utf8mb4' '--batch' '--skip-column-names' '--raw' "--database=$Database" "--execute=$Sql"
  if($LASTEXITCODE -ne 0){throw 'MySQL proof query failed'}
  return ($value -join "`n")
}
function Sha([string]$Value){
  $algorithm=[Security.Cryptography.SHA256]::Create()
  try{return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-','').ToLowerInvariant()}
  finally{$algorithm.Dispose()}
}
function Tables([string]$Database){
  return Query 'information_schema' "SELECT TABLE_NAME FROM TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_TYPE='BASE TABLE' ORDER BY BINARY TABLE_NAME"
}
function Rows([string]$Database,[string]$Sql){
  $digests=Query $Database $Sql
  $count=0
  if($digests.Length -gt 0){
    $lines=@($digests -split "`n")
    foreach($line in $lines){if($line -notmatch '^[a-f0-9]{64}$'){throw 'Unexpected row digest format'}}
    $count=$lines.Count
  }
  return @{rowCount=$count;sha256=(Sha $digests)}
}
function Definition([string]$Database,[string]$Table){
  # SHOW CREATE spelling can change on restore (for example, an inherited CHARACTER SET becomes
  # explicit). Compare resolved metadata instead. Actual charset/collation, defaults, generated
  # expressions, indexes and constraints remain part of the fingerprint. Only an intra-database
  # foreign-key schema name is normalized, because the restored schema deliberately has a new name.
  $queries=[ordered]@{
    table="SELECT SHA2(CAST(JSON_ARRAY(ENGINE,ROW_FORMAT,TABLE_COLLATION,CREATE_OPTIONS,TABLE_COMMENT,AUTO_INCREMENT) AS CHAR CHARACTER SET utf8mb4),256) FROM TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$Table'"
    columns="SELECT SHA2(CAST(JSON_ARRAY(ORDINAL_POSITION,COLUMN_NAME,COLUMN_DEFAULT,IS_NULLABLE,DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_OCTET_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_TYPE,COLUMN_KEY,EXTRA,COLUMN_COMMENT,GENERATION_EXPRESSION,SRS_ID) AS CHAR CHARACTER SET utf8mb4),256) FROM COLUMNS WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$Table' ORDER BY ORDINAL_POSITION"
    indexes="SELECT SHA2(CAST(JSON_ARRAY(INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,SUB_PART,PACKED,NULLABLE,INDEX_TYPE,COMMENT,INDEX_COMMENT,IS_VISIBLE,EXPRESSION) AS CHAR CHARACTER SET utf8mb4),256) FROM STATISTICS WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$Table' ORDER BY BINARY INDEX_NAME,SEQ_IN_INDEX"
    constraints="SELECT SHA2(CAST(JSON_ARRAY(CONSTRAINT_NAME,CONSTRAINT_TYPE,ENFORCED) AS CHAR CHARACTER SET utf8mb4),256) FROM TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$Table' ORDER BY BINARY CONSTRAINT_NAME"
    keys="SELECT SHA2(CAST(JSON_ARRAY(CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,POSITION_IN_UNIQUE_CONSTRAINT,IF(REFERENCED_TABLE_SCHEMA='$Database','SELF',REFERENCED_TABLE_SCHEMA),REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME) AS CHAR CHARACTER SET utf8mb4),256) FROM KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$Table' ORDER BY BINARY CONSTRAINT_NAME,ORDINAL_POSITION"
    references="SELECT SHA2(CAST(JSON_ARRAY(CONSTRAINT_NAME,IF(UNIQUE_CONSTRAINT_SCHEMA='$Database','SELF',UNIQUE_CONSTRAINT_SCHEMA),UNIQUE_CONSTRAINT_NAME,MATCH_OPTION,UPDATE_RULE,DELETE_RULE,REFERENCED_TABLE_NAME) AS CHAR CHARACTER SET utf8mb4),256) FROM REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='$Database' AND TABLE_NAME='$Table' ORDER BY BINARY CONSTRAINT_NAME"
    checks="SELECT SHA2(CAST(JSON_ARRAY(t.CONSTRAINT_NAME,c.CHECK_CLAUSE) AS CHAR CHARACTER SET utf8mb4),256) FROM TABLE_CONSTRAINTS t JOIN CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA='$Database' AND t.TABLE_NAME='$Table' ORDER BY BINARY t.CONSTRAINT_NAME"
    partitions="SELECT SHA2(CAST(JSON_ARRAY(PARTITION_NAME,SUBPARTITION_NAME,PARTITION_ORDINAL_POSITION,SUBPARTITION_ORDINAL_POSITION,PARTITION_METHOD,SUBPARTITION_METHOD,PARTITION_EXPRESSION,SUBPARTITION_EXPRESSION,PARTITION_DESCRIPTION,TABLESPACE_NAME) AS CHAR CHARACTER SET utf8mb4),256) FROM PARTITIONS WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$Table' ORDER BY PARTITION_ORDINAL_POSITION,SUBPARTITION_ORDINAL_POSITION"
  }
  $parts=@()
  foreach($entry in $queries.GetEnumerator()){$parts+=($entry.Key+'='+(Sha (Query 'information_schema' $entry.Value)))}
  return Sha ($parts -join "`n")
}
# Discover every base table instead of assuming a phase-three schema. Only hashes/counts leave
# the private process. HEX preserves null/empty, binary and exact text without ambiguous delimiters.
$taskSourceTables=Tables $SourceDatabase
if([string]::IsNullOrWhiteSpace($taskSourceTables)){throw 'Source database has no base tables'}
$taskTables=@($taskSourceTables -split "`n")
if($taskTables -notcontains 'harness_schema'){throw 'Source is not a Harness database'}
$taskQueries=[ordered]@{}
$taskBefore=[ordered]@{}
foreach($table in $taskTables){
  if($table -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,63}$'){throw 'Unsupported table identifier'}
  $columnText=Query 'information_schema' "SELECT COLUMN_NAME FROM COLUMNS WHERE TABLE_SCHEMA='$SourceDatabase' AND TABLE_NAME='$table' ORDER BY ORDINAL_POSITION"
  if([string]::IsNullOrWhiteSpace($columnText)){throw "Missing columns for table $table"}
  $expressions=@()
  foreach($column in ($columnText -split "`n")){
    if($column -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,63}$'){throw 'Unsupported column identifier'}
    $expressions+=('IF(`{0}` IS NULL,NULL,HEX(CAST(`{0}` AS BINARY)))' -f $column)
  }
  $taskQueries[$table]='SELECT SHA2(CAST(JSON_ARRAY('+($expressions -join ',')+') AS CHAR CHARACTER SET utf8mb4),256) AS row_digest FROM `'+$table+'` ORDER BY row_digest'
  $rowProof=Rows $SourceDatabase $taskQueries[$table]
  $rowProof['definitionSha256']=Definition $SourceDatabase $table
  $taskBefore[$table]=$rowProof
}
& $taskDumpProgram "--defaults-extra-file=$taskOption" '--single-transaction' '--skip-lock-tables' '--no-tablespaces' '--set-gtid-purged=OFF' '--hex-blob' '--default-character-set=utf8mb4' "--result-file=$taskDump" $SourceDatabase
if($LASTEXITCODE -ne 0){throw 'Backup failed; private partial file retained'}
$existing=Query 'information_schema' "SELECT COUNT(*) FROM SCHEMATA WHERE SCHEMA_NAME='$taskRestore'"
if($existing -ne '0'){throw 'Refusing to reuse a restore database'}
$null=Query 'information_schema' "CREATE DATABASE $taskRestore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
$null=Query $taskRestore ('source '+$taskDump.Replace('\','/'))
if((Tables $SourceDatabase) -cne $taskSourceTables){throw 'Source table set changed during proof; quiesce workers and use a new directory'}
if((Tables $taskRestore) -cne $taskSourceTables){throw 'Restored table set differs from source'}
$checks=@()
foreach($table in $taskTables){
  $source=Rows $SourceDatabase $taskQueries[$table]
  $restored=Rows $taskRestore $taskQueries[$table]
  $sourceDefinition=Definition $SourceDatabase $table
  $restoredDefinition=Definition $taskRestore $table
  if($source.sha256 -ne $taskBefore[$table].sha256 -or $source.rowCount -ne $taskBefore[$table].rowCount -or $sourceDefinition -ne $taskBefore[$table].definitionSha256){throw "Source changed during proof: $table; quiesce workers and use a new directory"}
  if($source.sha256 -ne $restored.sha256 -or $source.rowCount -ne $restored.rowCount -or $sourceDefinition -ne $restoredDefinition){throw "Restored table differs: $table"}
  $checks+=@{tableGroup=$table;tableName=$table;rowCount=$source.rowCount;sha256=$source.sha256;definitionSha256=$sourceDefinition;exactMatch=$true}
}
if((Tables $SourceDatabase) -cne $taskSourceTables){throw 'Source table set changed while checking restore'}
$proof=@{schemaVersion=2;status='PASSED';at=[DateTime]::UtcNow.ToString('o');sourceDatabase=$SourceDatabase;restoredDatabase=$taskRestore;mysqlVersion=(Query $taskRestore 'SELECT VERSION()');backupSha256=(Get-FileHash -LiteralPath $taskDump -Algorithm SHA256).Hash.ToLowerInvariant();tableDiscovery='ALL_BASE_TABLES';definitionComparison='MYSQL_INFORMATION_SCHEMA_V1';checks=$checks;restoredWorkersStarted=$false;sourceDatabaseOverwritten=$false;privateBackupRetained=$true;boundary='Every discovered base table and semantic definition compared after logical restore into a fresh isolated schema; no external action replay, production RPO/RTO, or identity database restore claimed'}
$proof | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Report -Encoding utf8
@{status='PASSED';report=$Report;restoredDatabase=$taskRestore;tableGroups=$checks.Count} | ConvertTo-Json
