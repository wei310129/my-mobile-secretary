[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot
. (Join-Path $scriptsRoot '_devops-common.ps1')
$script:assertions = 0

function Assert-SecretResolution {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

$commonText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $scriptsRoot '_devops-common.ps1')
$startText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $scriptsRoot 'dev-start.ps1')
Assert-SecretResolution ($commonText.Contains('worktree list --porcelain')) `
    'secret resolution did not require registered Git worktree metadata'
Assert-SecretResolution ($commonText.Contains('rev-parse --git-common-dir')) `
    'secret resolution did not verify the Git common-dir'
Assert-SecretResolution ($commonText.Contains('Initialize-LocalApplicationSecretEnvironment')) `
    'application secret environment initializer is missing'
Assert-SecretResolution ($startText.Contains('Initialize-LocalApplicationSecretEnvironment')) `
    'dev-start does not initialize worktree-safe application secrets'

$primaryRoot = Resolve-RegisteredPrimaryRepositoryRoot
Assert-SecretResolution ($primaryRoot -and (Test-Path -LiteralPath (Join-Path $primaryRoot '.git') -PathType Container)) `
    'registered primary worktree could not be resolved safely'

$worktreeSecrets = Join-Path $repoRoot 'secrets.yaml'
$hadWorktreeSecrets = Test-Path -LiteralPath $worktreeSecrets -PathType Leaf
$before = $env:CONVERSATION_SCOPE_HMAC_KEY_BASE64
try {
    Initialize-LocalApplicationSecretEnvironment
    $resolved = Get-LocalSecretValue -Name 'CONVERSATION_SCOPE_HMAC_KEY_BASE64'
    if ($resolved) {
        Assert-SecretResolution (-not [string]::IsNullOrWhiteSpace($env:CONVERSATION_SCOPE_HMAC_KEY_BASE64)) `
            'available primary-root HMAC key was not passed to the child application environment'
    }
    Assert-SecretResolution ((Test-Path -LiteralPath $worktreeSecrets -PathType Leaf) -eq $hadWorktreeSecrets) `
        'secret resolution copied or created a worktree secrets file'
} finally {
    if ($null -eq $before) { Remove-Item Env:CONVERSATION_SCOPE_HMAC_KEY_BASE64 -ErrorAction SilentlyContinue }
    else { $env:CONVERSATION_SCOPE_HMAC_KEY_BASE64 = $before }
}

[pscustomobject]@{
    status = 'passed'
    assertions = $assertions
    primaryWorktreeVerified = $true
    secretValueOutput = 'none'
    worktreeSecretsMutated = $false
} | ConvertTo-Json -Compress
