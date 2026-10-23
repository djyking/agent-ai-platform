param(
  [Parameter(Mandatory=$true)][string]$DefaultsFile,
  [Parameter(Mandatory=$true)][string]$Database,
  [Parameter(Mandatory=$true)][string]$Destination,
  [string]$MySqlDump = 'mysqldump'
)
$ErrorActionPreference = 'Stop'
if ($Database -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') { throw 'Invalid database name' }
$sourceConfig = (Resolve-Path -LiteralPath $DefaultsFile).Path
$outputPath = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $outputPath) { throw 'Backup destination already exists' }
# --result-file avoids PowerShell encoding changes. Credentials only exist in the private option file.
& $MySqlDump "--defaults-extra-file=$sourceConfig" '--single-transaction' '--skip-lock-tables' '--no-tablespaces' '--set-gtid-purged=OFF' '--hex-blob' '--default-character-set=utf8mb4' "--result-file=$outputPath" $Database
if ($LASTEXITCODE -ne 0) { throw "Backup failed ($LASTEXITCODE); incomplete output retained for diagnosis" }
Get-FileHash -LiteralPath $outputPath -Algorithm SHA256 | Select-Object Hash,Path
