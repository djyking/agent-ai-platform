param(
  [Parameter(Mandatory=$true)][string]$Config,
  [int]$Port = 8097,
  [string]$Jar = "$PSScriptRoot/../../harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar"
)
$ErrorActionPreference = 'Stop'
foreach ($name in 'HARNESS_JDBC_URL','HARNESS_JDBC_USER','HARNESS_JDBC_PASSWORD','HARNESS_SIGNING_KEY','HARNESS_APP_CREDENTIAL') {
  if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Required environment variable missing: $name" }
}
$env:HARNESS_CONFIG = (Resolve-Path -LiteralPath $Config).Path
$env:HARNESS_PORT = [string]$Port
& java '-Dfile.encoding=UTF-8' -jar (Resolve-Path -LiteralPath $Jar).Path
exit $LASTEXITCODE
