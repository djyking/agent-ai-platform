param(
    [ValidateSet('Discover', 'Write')][string]$Mode = 'Discover',
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Report,
    [string]$SourceBranch = 'main',
    [string]$AuthorizedScope
)

$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}$' -or
    $Tag -notmatch '^[a-z0-9][a-z0-9-]{7,48}$') {
    throw 'Invalid explicit repository or acceptance tag'
}
$acceptanceBranch = 'codex/harness-write-acceptance-' + $Tag
if ($Mode -eq 'Write' -and $AuthorizedScope -cne ($Repository + '@' + $acceptanceBranch)) {
    throw 'Write requires AuthorizedScope exactly matching repository@acceptance-branch'
}
$acceptanceRoot = Split-Path -Parent $PSScriptRoot
$acceptanceReport = [System.IO.Path]::GetFullPath($Report)
if (Test-Path -LiteralPath $acceptanceReport) { throw 'Choose a new report file; existing evidence is never overwritten' }
$acceptanceKeys = @('HARNESS_MCP_TOKEN', 'HARNESS_GITHUB_REPOSITORY', 'HARNESS_GITHUB_ACCEPTANCE_TAG',
    'HARNESS_GITHUB_SOURCE_BRANCH', 'HARNESS_GITHUB_WRITE_CONFIRM', 'GIT_TERMINAL_PROMPT', 'GCM_INTERACTIVE')
$previousEnvironment = @{}
foreach ($key in $acceptanceKeys) { $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$existingCredential = @{}
try {
    if ([string]::IsNullOrWhiteSpace($env:HARNESS_MCP_TOKEN)) {
        $env:GIT_TERMINAL_PROMPT = '0'
        $env:GCM_INTERACTIVE = 'Never'
        $credentialInput = "protocol=https`nhost=github.com`npath=$Repository.git`n`n"
        $credentialLines = $credentialInput | git -C $acceptanceRoot -c credential.interactive=never credential fill 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'No existing non-interactive GitHub credential; inject HARNESS_MCP_TOKEN explicitly' }
        foreach ($line in $credentialLines) {
            $parts = $line -split '=', 2
            if ($parts.Count -eq 2) { $existingCredential[$parts[0]] = $parts[1] }
        }
        if (-not $existingCredential.ContainsKey('password')) { throw 'Existing GitHub credential unavailable' }
        $env:HARNESS_MCP_TOKEN = $existingCredential['password']
    }
    $env:HARNESS_GITHUB_REPOSITORY = $Repository
    $env:HARNESS_GITHUB_ACCEPTANCE_TAG = $Tag
    $env:HARNESS_GITHUB_SOURCE_BRANCH = $SourceBranch
    $env:HARNESS_GITHUB_WRITE_CONFIRM = $AuthorizedScope
    $acceptanceCommand = if ($Mode -eq 'Write') { 'github-write' } else { 'github-write-discover' }
    Push-Location -LiteralPath $acceptanceRoot
    try {
        $execArguments = '-Dexec.args=' + $acceptanceCommand + ' "' + $acceptanceReport + '"'
        & (Join-Path $acceptanceRoot 'mvnw.cmd') -pl harness-validation exec:java $execArguments -B -ntp
        if ($LASTEXITCODE -ne 0) { throw 'GitHub acceptance failed; inspect the sanitized report before any further write' }
    } finally { Pop-Location }
} finally {
    foreach ($key in $acceptanceKeys) { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    $existingCredential.Clear()
    $credentialLines = $null
    $credentialInput = $null
    $parts = $null
    $line = $null
}
