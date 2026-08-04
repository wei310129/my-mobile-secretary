$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\_devops-common.ps1"

function Assert-Version {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function New-VersionFixture {
    param(
        [string]$Sha = '20de79679f41f3144b8a56afe7d462fa10be01a7',
        [long]$BuildNumber = 199,
        [bool]$Dirty = $false,
        [bool]$Available = $true
    )
    return [pscustomobject]@{
        Available = $Available
        GitSha = $Sha
        BuildNumber = $BuildNumber
        Dirty = $Dirty
    }
}

$current = Compare-ServiceVersion -Running (New-VersionFixture) -Checkout (New-VersionFixture)
Assert-Version ($current.Status -eq 'CURRENT') 'matching clean versions must be CURRENT'

$stale = Compare-ServiceVersion `
    -Running (New-VersionFixture -Sha '1111111111111111111111111111111111111111') `
    -Checkout (New-VersionFixture)
Assert-Version ($stale.Status -eq 'STALE') 'different SHA must be STALE'

$dirty = Compare-ServiceVersion -Running (New-VersionFixture -Dirty $true) -Checkout (New-VersionFixture)
Assert-Version ($dirty.Status -eq 'DIRTY') 'dirty source must be DIRTY'

$unknown = Compare-ServiceVersion `
    -Running (New-VersionFixture -Available $false) `
    -Checkout (New-VersionFixture)
Assert-Version ($unknown.Status -eq 'UNKNOWN') 'missing metadata must be UNKNOWN'

$checkout = Get-CheckoutServiceVersion
Assert-Version $checkout.Available 'current repository checkout metadata must be readable'
Assert-Version ($checkout.BuildNumber -gt 0) 'checkout build number must be positive'
Assert-Version ($checkout.GitSha -match '^[0-9a-f]{40}$') 'checkout SHA must be canonical'

Write-Output 'Service version comparison gate passed.'
