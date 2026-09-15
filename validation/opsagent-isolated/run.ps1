param(
    [Parameter(Mandatory = $true)][string]$OpsAgentRoot,
    [switch]$AllowDependencyDownload
)
$ErrorActionPreference = 'Stop'
$platformRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$preparedPath = & python (Join-Path $PSScriptRoot 'prepare.py') --opsagent-root $OpsAgentRoot
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare isolated source snapshot.' }
$snapshotPath = [System.IO.Path]::GetFullPath(($preparedPath | Select-Object -Last 1))
$allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $platformRoot '.work/opsagent-isolated')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $snapshotPath.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Generated snapshot is outside the dedicated isolated workspace.'
}
$buildArgs = @('-s', (Join-Path $platformRoot '.mvn/settings.xml'), '-f', (Join-Path $snapshotPath 'pom.xml'),
    '-pl', 'isolation-tests', '-am', 'test', '-B', '-ntp', '-Dcheckstyle.skip=true',
    '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=IsolatedOpsAgentPilotTest',
    ('-Dcsp.sentinel.log.dir=' + (Join-Path $snapshotPath 'sentinel-logs')))
if (-not $AllowDependencyDownload) { $buildArgs += '-o' }
Write-Host ('Isolated source snapshot: ' + $snapshotPath)
Write-Host 'No original resources/configuration are copied; runtime uses loopback HTTP and synthetic H2 data only.'
Push-Location $platformRoot
try {
    & (Join-Path $platformRoot 'mvnw.cmd') @buildArgs
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}
Write-Host ('Evidence: ' + (Join-Path $snapshotPath 'isolation-tests/target/surefire-reports'))
& python (Join-Path $PSScriptRoot 'finalize.py') --snapshot $snapshotPath --maven-exit $buildExit
$reportExit = $LASTEXITCODE
if ($buildExit -ne 0) { throw ('Isolated pilot failed with Maven exit code ' + $buildExit) }
if ($reportExit -ne 0) { throw 'Isolated pilot report or source-preservation verification failed.' }
