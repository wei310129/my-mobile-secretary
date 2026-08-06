[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseRef,
    [Parameter(Mandatory)][string]$HeadRef,
    [Parameter(Mandatory)][string]$BaseSha,
    [Parameter(Mandatory)][string]$HeadSha,
    [string]$ChangedFilesJson,
    [switch]$UseWorkingTreeChanges,
    [string]$RepositoryRoot,
    [string]$PolicyPath,
    [string]$HandoffRoot,
    [switch]$SkipAncestryValidation,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'

if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
if (-not $PolicyPath) { $PolicyPath = Join-Path $RepositoryRoot '.github\merge-policy.json' }
if (-not $HandoffRoot) { $HandoffRoot = Join-Path $RepositoryRoot 'docs\exec-plans\active\handoffs' }

function Fail-Policy {
    param([Parameter(Mandatory)][string]$Message)
    throw "MERGE_POLICY_FAILED: $Message"
}

function Normalize-Path {
    param([Parameter(Mandatory)][string]$Path)
    return $Path.Replace('\', '/').TrimStart('/')
}

function Test-PathPattern {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Pattern
    )
    $normalizedPath = Normalize-Path $Path
    $normalizedPattern = (Normalize-Path $Pattern).Replace('**', '*')
    return $normalizedPath -like $normalizedPattern
}

function Test-AnyPattern {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Patterns
    )
    foreach ($pattern in $Patterns) {
        if (Test-PathPattern -Path $Path -Pattern ([string]$pattern)) { return $true }
    }
    return $false
}

function Assert-Sha {
    param([string]$Value, [string]$Context)
    if ($Value -notmatch '^[0-9a-f]{40}$') { Fail-Policy "$Context must contain a canonical 40-character SHA." }
}

function Test-GitAncestor {
    param([string]$Candidate, [string]$Descendant)
    & git -C $RepositoryRoot merge-base --is-ancestor $Candidate $Descendant 2>$null
    return $LASTEXITCODE -eq 0
}

if (-not (Test-Path -LiteralPath $PolicyPath -PathType Leaf)) { Fail-Policy "Policy file not found: $PolicyPath" }
if ([bool]$ChangedFilesJson -eq [bool]$UseWorkingTreeChanges) {
    Fail-Policy 'Specify exactly one of ChangedFilesJson or UseWorkingTreeChanges.'
}
if ($ChangedFilesJson -and -not (Test-Path -LiteralPath $ChangedFilesJson -PathType Leaf)) {
    Fail-Policy "Changed-files JSON not found: $ChangedFilesJson"
}

$policy = Get-Content -LiteralPath $PolicyPath -Raw -Encoding utf8 | ConvertFrom-Json
if ($policy.schemaVersion -ne 1) { Fail-Policy 'Unsupported policy schemaVersion.' }
if ($BaseRef -ne $policy.protectedBase) { Fail-Policy "PR base must be '$($policy.protectedBase)', got '$BaseRef'." }
if ($HeadRef -eq $BaseRef) { Fail-Policy 'Head branch must differ from the protected base.' }
Assert-Sha -Value $BaseSha -Context 'BaseSha'
Assert-Sha -Value $HeadSha -Context 'HeadSha'

$matchingPolicies = @($policy.branchPolicies | Where-Object { $HeadRef -match $_.branchRegex })
if ($matchingPolicies.Count -ne 1) {
    Fail-Policy "Head branch '$HeadRef' must match exactly one branch policy; matched $($matchingPolicies.Count)."
}
$branchPolicy = $matchingPolicies[0]

if ($UseWorkingTreeChanges) {
    $changedFiles = @(& git -C $RepositoryRoot status --porcelain=v1 -uall | ForEach-Object {
        $code = $_.Substring(0, 2)
        $path = $_.Substring(3)
        if ($path -match ' -> ') { $path = ($path -split ' -> ', 2)[1] }
        $status = if ($code -eq '??' -or $code -match 'A') {
            'added'
        } elseif ($code -match 'D') {
            'removed'
        } elseif ($code -match 'R') {
            'renamed'
        } else {
            'modified'
        }
        [pscustomobject]@{ filename = $path; status = $status }
    })
} else {
    $parsedChangedFiles = Get-Content -LiteralPath $ChangedFilesJson -Raw -Encoding utf8 | ConvertFrom-Json
    $changedFiles = @($parsedChangedFiles | ForEach-Object { $_ })
}
if ($changedFiles.Count -eq 0) { Fail-Policy 'PR contains no changed files.' }

$requiresSerial = $false
$normalizedFiles = [Collections.Generic.List[object]]::new()
foreach ($file in $changedFiles) {
    $path = Normalize-Path ([string]$file.filename)
    $status = ([string]$file.status).ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($path)) { Fail-Policy 'Changed file is missing filename.' }
    if ($status -notin @('added', 'modified', 'removed', 'renamed', 'copied', 'changed')) {
        Fail-Policy "Unsupported changed-file status '$status' for '$path'."
    }
    if (-not (Test-AnyPattern -Path $path -Patterns @($branchPolicy.allowedPaths))) {
        Fail-Policy "Branch policy '$($branchPolicy.name)' does not allow '$path'."
    }
    if (Test-AnyPattern -Path $path -Patterns @($branchPolicy.forbiddenPaths)) {
        Fail-Policy "Branch policy '$($branchPolicy.name)' explicitly forbids '$path'."
    }
    if ($status -in @('removed', 'renamed') -and
            (Test-AnyPattern -Path $path -Patterns @($policy.sensitiveDestructivePaths))) {
        Fail-Policy "Sensitive path '$path' cannot be removed or renamed by an agent merge."
    }
    if (Test-AnyPattern -Path $path -Patterns @($policy.serialRegressionPaths)) { $requiresSerial = $true }
    $normalizedFiles.Add([pscustomobject]@{ path = $path; status = $status })
}

if ($branchPolicy.PSObject.Properties['exactChangedFiles'] -and
        $normalizedFiles.Count -ne [int]$branchPolicy.exactChangedFiles) {
    Fail-Policy "Branch policy '$($branchPolicy.name)' requires exactly $($branchPolicy.exactChangedFiles) changed files."
}
if ($branchPolicy.PSObject.Properties['requiredPathPatterns']) {
    foreach ($requiredPattern in @($branchPolicy.requiredPathPatterns)) {
        $matches = @($normalizedFiles | Where-Object { Test-PathPattern -Path $_.path -Pattern ([string]$requiredPattern) })
        if ($matches.Count -ne 1) {
            Fail-Policy "Branch policy '$($branchPolicy.name)' requires exactly one path matching '$requiredPattern'."
        }
    }
}

$migrationPrefix = 'src/main/resources/db/migration/'
$changedMigrations = @($normalizedFiles | Where-Object { $_.path -like "$migrationPrefix*" })
foreach ($migration in $changedMigrations) {
    if ($migration.status -ne 'added') {
        Fail-Policy "Existing Flyway migration '$($migration.path)' is immutable; only added migrations are accepted."
    }
    if ((Split-Path -Leaf $migration.path) -notmatch '^V([0-9]+)__.+[.]sql$') {
        Fail-Policy "Migration '$($migration.path)' does not use V<integer>__<description>.sql."
    }
}

$migrationRoot = Join-Path $RepositoryRoot ($migrationPrefix.Replace('/', '\'))
if (Test-Path -LiteralPath $migrationRoot) {
    $versions = @{}
    Get-ChildItem -LiteralPath $migrationRoot -Filter 'V*__*.sql' -File | ForEach-Object {
        if ($_.Name -match '^V([0-9]+)__') {
            $version = [int]$Matches[1]
            if ($versions.ContainsKey($version)) {
                Fail-Policy "Duplicate Flyway version V${version}: '$($versions[$version])' and '$($_.Name)'."
            }
            $versions[$version] = $_.Name
        }
    }
}

$laptopStatePath = Join-Path $HandoffRoot 'laptop-trigger-state.json'
$desktopStatePath = Join-Path $HandoffRoot 'desktop-trigger-state.json'
foreach ($statePath in @($laptopStatePath, $desktopStatePath)) {
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { Fail-Policy "Required handoff state missing: $statePath" }
}
$laptopState = Get-Content -LiteralPath $laptopStatePath -Raw -Encoding utf8 | ConvertFrom-Json
$desktopState = Get-Content -LiteralPath $desktopStatePath -Raw -Encoding utf8 | ConvertFrom-Json
if ($laptopState.ownerLane -ne 'laptop' -or $desktopState.ownerLane -ne 'desktop') {
    Fail-Policy 'Handoff ownerLane values are invalid.'
}

$durableEntries = [Collections.Generic.List[object]]::new()
foreach ($property in $laptopState.triggers.PSObject.Properties) {
    $durableEntries.Add([pscustomobject]@{ id = $property.Name; value = $property.Value })
}
foreach ($property in $desktopState.consumedLaptopTriggers.PSObject.Properties) {
    $durableEntries.Add([pscustomobject]@{ id = $property.Name; value = $property.Value })
}
foreach ($property in $desktopState.gates.PSObject.Properties) {
    $durableEntries.Add([pscustomobject]@{ id = $property.Name; value = $property.Value })
}
foreach ($entry in $durableEntries) {
    $status = [string]$entry.value.status
    if ($status -notin @($policy.allowedStatuses)) { Fail-Policy "Trigger '$($entry.id)' has invalid status '$status'." }
    $publishedSha = [string]$entry.value.publishedSha
    if ($status -in @('READY', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PASS', 'MERGED', 'CONSUMED')) {
        Assert-Sha -Value $publishedSha -Context "Trigger '$($entry.id)' publishedSha"
        if (-not $SkipAncestryValidation -and
                -not (Test-GitAncestor -Candidate $publishedSha -Descendant $BaseSha) -and
                -not (Test-GitAncestor -Candidate $publishedSha -Descendant $HeadSha)) {
            Fail-Policy "Trigger '$($entry.id)' publishedSha is not in PR base/head ancestry."
        }
    }
}

$activeConsumerStatuses = @('ACKNOWLEDGED', 'IN_PROGRESS', 'PASS', 'MERGED', 'CONSUMED')
foreach ($property in $desktopState.consumedLaptopTriggers.PSObject.Properties) {
    $consumerTrigger = $property.Value
    if ([string]$consumerTrigger.status -notin $activeConsumerStatuses) { continue }
    $producerProperty = $laptopState.triggers.PSObject.Properties[$property.Name]
    if ($null -eq $producerProperty) {
        Fail-Policy "Consumed laptop trigger '$($property.Name)' has no matching producer trigger."
    }
    $producerTrigger = $producerProperty.Value
    if ([string]$producerTrigger.status -notin @('READY', 'CONSUMED')) {
        Fail-Policy "Consumed laptop trigger '$($property.Name)' is active while producer status is '$($producerTrigger.status)'."
    }
    if ([string]$consumerTrigger.publishedSha -ne [string]$producerTrigger.publishedSha) {
        Fail-Policy "Consumed laptop trigger '$($property.Name)' publishedSha does not match producer state."
    }
}

foreach ($consumption in $policy.oneTimeTriggerConsumptions) {
    $producer = $laptopState.triggers.PSObject.Properties[[string]$consumption.producerTrigger].Value
    $consumer = $desktopState.gates.PSObject.Properties[[string]$consumption.consumerGate].Value
    if ($null -eq $producer -or $null -eq $consumer) { Fail-Policy 'Configured one-time trigger mapping is missing from handoff state.' }
    if ($consumer.status -eq 'MERGED') {
        $field = [string]$consumption.consumerVersionField
        $consumedVersion = [string]$consumer.$field
        if ([string]::IsNullOrWhiteSpace($consumedVersion)) {
            Fail-Policy "Consumer gate '$($consumption.consumerGate)' does not record its consumed version."
        }
        if ($producer.reservedVersion -ne $consumedVersion) {
            Fail-Policy "Producer trigger '$($consumption.producerTrigger)' reservedVersion does not match '$consumedVersion'."
        }
        if ($producer.status -ne 'CONSUMED' -or $producer.grantStatus -ne 'CONSUMED') {
            Fail-Policy "One-time trigger '$($consumption.producerTrigger)' must be CONSUMED after '$($consumption.consumerGate)' is MERGED."
        }
    }
}

if ($changedMigrations.Count -gt 0 -and $branchPolicy.name -like 'desktop-*') {
    $schemaGrantTrigger = [string]$branchPolicy.schemaGrantTrigger
    if ([string]::IsNullOrWhiteSpace($schemaGrantTrigger)) {
        Fail-Policy "Branch policy '$($branchPolicy.name)' does not own a schema handoff."
    }
    $grant = $laptopState.triggers.PSObject.Properties[$schemaGrantTrigger].Value
    if ($null -eq $grant) { Fail-Policy "Schema handoff '$schemaGrantTrigger' is missing." }
    if ($grant.status -ne 'READY' -or $grant.grantStatus -ne 'GRANTED_ONCE') {
        Fail-Policy 'Desktop migration requires an active GRANTED_ONCE schema handoff.'
    }
    if ($grant.ownerBranch -ne $HeadRef) { Fail-Policy 'Schema handoff ownerBranch does not match PR head branch.' }
    foreach ($migration in $changedMigrations) {
        [void]((Split-Path -Leaf $migration.path) -match '^V([0-9]+)__')
        if ("V$($Matches[1])" -ne $grant.reservedVersion) {
            Fail-Policy "Migration version does not match reserved schema version '$($grant.reservedVersion)'."
        }
    }
}

$result = [ordered]@{
    status = 'passed'
    branchPolicy = $branchPolicy.name
    changedFiles = $changedFiles.Count
    changedMigrations = $changedMigrations.Count
    requiresSerial = $requiresSerial
    baseSha = $BaseSha
    headSha = $HeadSha
}
if ($Json) { $result | ConvertTo-Json -Compress } else { [pscustomobject]$result | Format-List }
