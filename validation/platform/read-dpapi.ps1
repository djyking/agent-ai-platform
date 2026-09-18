param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'
# Invoked only with stdout captured by the private process runner; never print to a console.
$encrypted = (Get-Content -Raw -LiteralPath $Path).Trim()
$protected = ConvertTo-SecureString $encrypted
$plain = [System.Net.NetworkCredential]::new('', $protected).Password
[Console]::Out.Write($plain)
$plain = $null
$encrypted = $null
