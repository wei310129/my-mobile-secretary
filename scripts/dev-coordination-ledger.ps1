[CmdletBinding()]
param([string]$StateRoot)

. "$PSScriptRoot\coordination-common.ps1"
if ([string]::IsNullOrWhiteSpace($StateRoot)) { $StateRoot = Get-CoordinationDefaultRoot }
$receiptDirectory = Join-Path $StateRoot 'receipts'
$receipts = @()
if (Test-Path -LiteralPath $receiptDirectory -PathType Container) {
    $receipts = @(Get-ChildItem -LiteralPath $receiptDirectory -Filter *.json -File | ForEach-Object {
        try { [IO.File]::ReadAllText($_.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json } catch { $null }
    } | Where-Object { $_ })
}
[pscustomobject]@{
    receiptCount = $receipts.Count
    outcomes = @($receipts | Group-Object outcome | ForEach-Object { [pscustomobject]@{ outcome=$_.Name; count=$_.Count } })
    dispositions = @($receipts | Group-Object disposition | ForEach-Object { [pscustomobject]@{ disposition=$_.Name; count=$_.Count } })
    recoveryRequired = @($receipts | Where-Object { $_.outcome -eq 'RECOVERY_REQUIRED' }).Count
    blocked = @($receipts | Where-Object { $_.outcome -eq 'BLOCKED' }).Count
} | ConvertTo-Json -Depth 6
