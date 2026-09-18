param(
    [Parameter(Mandatory = $true)][string]$Snapshot,
    [Parameter(Mandatory = $true)][string]$PrivateConfig
)
$ErrorActionPreference = 'Stop'
$platformRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$snapshotPath = (Resolve-Path -LiteralPath $Snapshot).Path
$snapshotRoot = [System.IO.Path]::GetFullPath((Join-Path $platformRoot '.work/opsagent-isolated')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $snapshotPath.StartsWith($snapshotRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Snapshot must be an already tested isolated source snapshot.'
}
$configPath = (Resolve-Path -LiteralPath $PrivateConfig).Path
$reportPath = Join-Path $snapshotPath 'isolation-tests/target/surefire-reports/TEST-com.opsagent.isolation.IsolatedIdentityBridgeTest.xml'
[xml]$testReport = Get-Content -LiteralPath $reportPath -Raw
if ([int]$testReport.testsuite.failures -ne 0 -or [int]$testReport.testsuite.errors -ne 0) {
    throw 'Identity bridge tests must pass before launching the local acceptance host.'
}
$testClasspath = ($testReport.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
if ([string]::IsNullOrWhiteSpace($testClasspath)) { throw 'Surefire test classpath was not recorded.' }
$launchId = [Guid]::NewGuid().ToString('N')
$argsPath = Join-Path $snapshotPath ('bridge-' + $launchId + '.args')
$logPath = Join-Path $snapshotPath ('bridge-' + $launchId + '.log')
$errorPath = Join-Path $snapshotPath ('bridge-' + $launchId + '.stderr.log')
$javaArgs = @('-Dfile.encoding=UTF-8', '-cp', ('"' + $testClasspath.Replace('\', '/') + '"'),
    'com.opsagent.isolation.IsolatedBridgeMain', ('"' + $configPath.Replace('\', '/') + '"'))
[System.IO.File]::WriteAllLines($argsPath, $javaArgs, [System.Text.UTF8Encoding]::new($false))
$javaPath = (Get-Command java).Source
$process = Start-Process -FilePath $javaPath -ArgumentList ('@"' + $argsPath + '"') -WorkingDirectory $snapshotPath `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $logPath -RedirectStandardError $errorPath
[PSCustomObject]@{ processId = $process.Id; log = $logPath; stderr = $errorPath; configFile = $configPath }
