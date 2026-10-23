param([switch]$SkipTests)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOriginalPath=$env:PATH
try {
  if(-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
    $taskBundled=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies'
    if(Test-Path -LiteralPath (Join-Path $taskBundled 'bin/fallback/pnpm.cmd')) {
      $env:PATH=(Join-Path $taskBundled 'node/bin')+';'+(Join-Path $taskBundled 'bin/fallback')+';'+$env:PATH
    }
  }
  $taskPnpm=(Get-Command pnpm -ErrorAction Stop).Source
  & $taskPnpm --dir (Join-Path $taskRoot 'platform-console') install --frozen-lockfile
  if($LASTEXITCODE -ne 0){throw 'Console dependency installation failed'}
  if(-not $SkipTests){
    & $taskPnpm --dir (Join-Path $taskRoot 'platform-console') test
    if($LASTEXITCODE -ne 0){throw 'Console tests failed'}
    & node --test (Join-Path $taskRoot 'support-pilot/src/test/js/approval.test.mjs')
    if($LASTEXITCODE -ne 0){throw 'Support approval UI tests failed'}
  }
  & $taskPnpm --dir (Join-Path $taskRoot 'platform-console') build
  if($LASTEXITCODE -ne 0){throw 'Console build failed'}
  if(-not (Test-Path -LiteralPath (Join-Path $taskRoot 'platform-console/dist/index.html'))){throw 'Console entry point missing'}
  Push-Location $taskRoot
  try {
    $taskArgs=@('clean','verify','-B','-ntp')
    if($SkipTests){$taskArgs+='-DskipTests'}
    & (Join-Path $taskRoot 'mvnw.cmd') @taskArgs
    if($LASTEXITCODE -ne 0){throw 'Java build failed'}
  } finally {Pop-Location}
} finally {$env:PATH=$taskOriginalPath}
