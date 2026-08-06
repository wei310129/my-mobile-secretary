[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('ValidateRequest', 'PrepareState', 'ValidateStatePr', 'RebuildState', 'Finalize')]
    [string]$Mode,
    [Parameter(Mandatory)][string]$RequestPath,
    [string]$RepositoryRoot,
    [string]$PolicyPath,
    [string]$StatePath,
    [string]$BaseStatePath,
    [string]$HeadStatePath,
    [string]$ReceiptPath,
    [string]$ChangedFilesJson,
    [string]$ProductMergeSha,
    [string]$ProductPrUrl,
    [string]$WorkflowRunUrl,
    [string]$PublishedAt,
    [string]$BaseSha,
    [switch]$SkipAncestryValidation,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'

if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
if (-not $PolicyPath) { $PolicyPath = Join-Path $RepositoryRoot '.github\producer-handoff-policy.json' }

function Fail-ProducerHandoff {
    param([Parameter(Mandatory)][string]$Message)
    throw "PRODUCER_HANDOFF_FAILED: $Message"
}

function Read-JsonDocument {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Context)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail-ProducerHandoff "$Context not found: $Path" }
    try { return Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json }
    catch { Fail-ProducerHandoff "$Context is not valid JSON: $($_.Exception.Message)" }
}

function Write-JsonDocument {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][object]$Document)
    $parent = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ('.producer-handoff-' + [guid]::NewGuid().ToString('n') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary, (($Document | ConvertTo-Json -Depth 30) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
    }
}

function Normalize-RepositoryPath {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Context)
    if ([string]::IsNullOrWhiteSpace($Path)) { Fail-ProducerHandoff "$Context is empty." }
    $candidate = $Path.Replace('\', '/')
    if ([IO.Path]::IsPathRooted($Path)) {
        $full = [IO.Path]::GetFullPath($Path)
        $prefix = $RepositoryRoot + [IO.Path]::DirectorySeparatorChar
        if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            Fail-ProducerHandoff "$Context is outside the repository."
        }
        $candidate = $full.Substring($prefix.Length).Replace('\', '/')
    }
    $candidate = $candidate.TrimStart('/')
    if ($candidate -match '(^|/)\.\.(/|$)' -or $candidate -match '(^|/)\.(/|$)') {
        Fail-ProducerHandoff "$Context contains a relative path segment."
    }
    return $candidate
}

function Resolve-RepositoryPath {
    param([Parameter(Mandatory)][string]$RelativePath)
    return Join-Path $RepositoryRoot ($RelativePath.Replace('/', '\'))
}

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)][object]$Document,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Context
    )
    $actual = @($Document.PSObject.Properties.Name)
    $missing = @($Expected | Where-Object { $_ -notin $actual })
    $unknown = @($actual | Where-Object { $_ -notin $Expected })
    if ($missing.Count -gt 0) { Fail-ProducerHandoff "$Context is missing: $($missing -join ', ')." }
    if ($unknown.Count -gt 0) { Fail-ProducerHandoff "$Context contains unknown fields: $($unknown -join ', ')." }
}

function ConvertTo-CanonicalValue {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [Collections.IDictionary]) {
        $ordered = [ordered]@{}
        foreach ($key in @($Value.Keys | ForEach-Object { [string]$_ } | Sort-Object)) {
            $ordered[$key] = ConvertTo-CanonicalValue $Value[$key]
        }
        return $ordered
    }
    if ($Value -is [Management.Automation.PSCustomObject]) {
        $ordered = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties | Sort-Object Name)) {
            $ordered[$property.Name] = ConvertTo-CanonicalValue $property.Value
        }
        return $ordered
    }
    if ($Value -is [Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @()
        foreach ($item in $Value) { $items += ,(ConvertTo-CanonicalValue $item) }
        return ,$items
    }
    return $Value
}

function Get-DocumentFingerprint {
    param([Parameter(Mandatory)][object]$Document)
    $canonical = ConvertTo-CanonicalValue $Document
    $json = $canonical | ConvertTo-Json -Depth 30 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ($hash | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Copy-JsonDocument {
    param([Parameter(Mandatory)][object]$Document)
    return $Document | ConvertTo-Json -Depth 30 | ConvertFrom-Json
}

function Set-DocumentProperty {
    param([Parameter(Mandatory)][object]$Document, [Parameter(Mandatory)][string]$Name, [AllowNull()][object]$Value)
    $property = $Document.PSObject.Properties[$Name]
    if ($null -eq $property) { $Document | Add-Member -NotePropertyName $Name -NotePropertyValue $Value }
    else { $property.Value = $Value }
}

function Assert-CanonicalSha {
    param([Parameter(Mandatory)][string]$Value, [Parameter(Mandatory)][string]$Context)
    if ($Value -notmatch '^[0-9a-f]{40}$') { Fail-ProducerHandoff "$Context must be a canonical 40-character SHA." }
}

function Assert-GitHubUrl {
    param([Parameter(Mandatory)][string]$Value, [Parameter(Mandatory)][string]$Context, [Parameter(Mandatory)][string]$Kind)
    if ($Value -notmatch "^https://github[.]com/[^/]+/[^/]+/$Kind(?:/|$)") {
        Fail-ProducerHandoff "$Context is not an expected GitHub URL."
    }
}

function Get-PolicyContext {
    $policy = Read-JsonDocument -Path $PolicyPath -Context 'Producer handoff policy'
    if ($policy.schemaVersion -ne 1) { Fail-ProducerHandoff 'Unsupported producer handoff policy schemaVersion.' }
    if (@($policy.events).Count -eq 0) { Fail-ProducerHandoff 'Producer handoff policy contains no events.' }
    return $policy
}

function Get-RequestContext {
    param([Parameter(Mandatory)][object]$Policy)
    $requestRelative = Normalize-RepositoryPath -Path $RequestPath -Context 'RequestPath'
    $requestAbsolute = Resolve-RepositoryPath $requestRelative
    $request = Read-JsonDocument -Path $requestAbsolute -Context 'Producer handoff request'
    Assert-ExactProperties -Document $request -Expected @('schemaVersion', 'requestId', 'eventId', 'evidence') -Context 'Producer handoff request'
    Assert-ExactProperties -Document $request.evidence -Expected @('planPath', 'gate') -Context 'Producer handoff evidence'
    if ($request.schemaVersion -ne 1) { Fail-ProducerHandoff 'Unsupported request schemaVersion.' }
    if ([string]$request.requestId -notmatch '^[a-z0-9][a-z0-9._-]{2,79}$') { Fail-ProducerHandoff 'requestId has an invalid format.' }
    if ([string]::IsNullOrWhiteSpace([string]$request.evidence.gate) -or ([string]$request.evidence.gate).Length -gt 160) {
        Fail-ProducerHandoff 'evidence.gate must contain 1-160 characters.'
    }
    $eventMatches = @($Policy.events | Where-Object { $_.eventId -eq [string]$request.eventId })
    if ($eventMatches.Count -ne 1) { Fail-ProducerHandoff "Event '$($request.eventId)' is not uniquely allowlisted." }
    $event = $eventMatches[0]
    $expectedRequest = "$($event.requestDirectory)/$($request.requestId).json"
    if ($requestRelative -cne $expectedRequest) { Fail-ProducerHandoff "RequestPath must be '$expectedRequest'." }
    $planRelative = Normalize-RepositoryPath -Path ([string]$request.evidence.planPath) -Context 'evidence.planPath'
    if ([string]::IsNullOrWhiteSpace([string]$event.evidencePlanPath) -or
            [string]::IsNullOrWhiteSpace([string]$event.evidenceGate)) {
        Fail-ProducerHandoff 'Allowlisted event is missing its immutable evidence contract.'
    }
    if ($planRelative -cne [string]$event.evidencePlanPath -or
            [string]$request.evidence.gate -cne [string]$event.evidenceGate) {
        Fail-ProducerHandoff 'Request evidence does not match the policy-owned plan and gate.'
    }
    if (-not (Test-Path -LiteralPath (Resolve-RepositoryPath $planRelative) -PathType Leaf)) {
        Fail-ProducerHandoff "Evidence plan does not exist: $planRelative"
    }
    if (@($event.resourceClaims).Count -ne 0 -or [int]$event.externalMutationCount -ne 0) {
        Fail-ProducerHandoff 'The v1 allowlisted event must have zero claims and zero external mutations.'
    }
    return [pscustomobject]@{
        Policy = $Policy
        Request = $request
        RequestRelative = $requestRelative
        RequestAbsolute = $requestAbsolute
        PlanRelative = $planRelative
        Event = $event
    }
}

function Get-StateContext {
    param(
        [Parameter(Mandatory)][object]$RequestContext,
        [string]$OverridePath
    )
    $relative = [string]$RequestContext.Event.statePath
    $absolute = if ($OverridePath) { [IO.Path]::GetFullPath($OverridePath) } else { Resolve-RepositoryPath $relative }
    $state = Read-JsonDocument -Path $absolute -Context 'Producer state'
    if ($state.schemaVersion -ne 1) { Fail-ProducerHandoff 'Unsupported producer state schemaVersion.' }
    if ([string]$state.ownerLane -ne [string]$RequestContext.Event.producerLane) {
        Fail-ProducerHandoff 'Producer state ownerLane does not match policy.'
    }
    $collection = $state.PSObject.Properties[[string]$RequestContext.Event.stateCollection].Value
    if ($null -eq $collection) { Fail-ProducerHandoff 'Producer state collection is missing.' }
    $trigger = $collection.PSObject.Properties[[string]$RequestContext.Event.eventId].Value
    if ($null -eq $trigger) { Fail-ProducerHandoff "Producer state is missing event '$($RequestContext.Event.eventId)'." }
    foreach ($requiredField in @('allowedPaths', 'forbiddenPaths', 'resourceClaims', 'externalMutationCountRequired')) {
        if ($null -eq $trigger.PSObject.Properties[$requiredField]) {
            Fail-ProducerHandoff "Producer trigger is missing policy field '$requiredField'."
        }
    }
    if ((Get-DocumentFingerprint @($trigger.allowedPaths)) -ne (Get-DocumentFingerprint @($RequestContext.Event.allowedPaths)) -or
            (Get-DocumentFingerprint @($trigger.forbiddenPaths)) -ne (Get-DocumentFingerprint @($RequestContext.Event.forbiddenPaths))) {
        Fail-ProducerHandoff 'Producer trigger evidence allowlist or forbidden paths do not match policy.'
    }
    if (@($trigger.resourceClaims).Count -ne 0 -or [int]$trigger.externalMutationCountRequired -ne 0) {
        Fail-ProducerHandoff 'Producer trigger must have zero resource claims and require zero external mutations.'
    }
    $scope = [ordered]@{
        schemaVersion = $state.schemaVersion
        ownerLane = [string]$state.ownerLane
        eventId = [string]$RequestContext.Event.eventId
        trigger = $trigger
    }
    return [pscustomobject]@{
        Relative = $relative
        Absolute = $absolute
        Document = $state
        Collection = $collection
        Trigger = $trigger
        Fingerprint = Get-DocumentFingerprint $scope
    }
}

function Get-PublishedDate {
    param([Parameter(Mandatory)][string]$Value)
    if ($Value -match '^\d{4}-\d{2}-\d{2}$') { return $Value }
    try { return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture).UtcDateTime.ToString('yyyy-MM-dd') }
    catch { Fail-ProducerHandoff 'PublishedAt is not a valid timestamp.' }
}

function Get-ReceiptRelativePath {
    param([Parameter(Mandatory)][object]$RequestContext, [Parameter(Mandatory)][string]$Sha)
    return "$($RequestContext.Event.receiptDirectory)/$($RequestContext.Request.requestId)--$($Sha.Substring(0, 12)).json"
}

function Apply-ExpectedStateTransition {
    param(
        [Parameter(Mandatory)][object]$RequestContext,
        [Parameter(Mandatory)][object]$BaseDocument,
        [Parameter(Mandatory)][string]$Sha,
        [Parameter(Mandatory)][string]$PublishedOn
    )
    $expected = Copy-JsonDocument $BaseDocument
    $collection = $expected.PSObject.Properties[[string]$RequestContext.Event.stateCollection].Value
    $trigger = $collection.PSObject.Properties[[string]$RequestContext.Event.eventId].Value
    Set-DocumentProperty -Document $trigger -Name 'status' -Value ([string]$RequestContext.Event.targetStatus)
    Set-DocumentProperty -Document $trigger -Name 'publishedSha' -Value $Sha
    Set-DocumentProperty -Document $trigger -Name 'baseSha' -Value $Sha
    if ($null -ne $trigger.PSObject.Properties['reason']) { $trigger.PSObject.Properties.Remove('reason') }
    Set-DocumentProperty -Document $expected -Name 'updatedOn' -Value $PublishedOn
    return $expected
}

function New-ReceiptDocument {
    param(
        [Parameter(Mandatory)][object]$RequestContext,
        [Parameter(Mandatory)][string]$Sha,
        [Parameter(Mandatory)][string]$PublishedOn,
        [Parameter(Mandatory)][string]$SourceFingerprint,
        [Parameter(Mandatory)][string]$TargetFingerprint,
        [Parameter(Mandatory)][int]$RebuildCount
    )
    return [ordered]@{
        schemaVersion = 1
        requestId = [string]$RequestContext.Request.requestId
        requestPath = [string]$RequestContext.RequestRelative
        eventId = [string]$RequestContext.Request.eventId
        producerLane = [string]$RequestContext.Event.producerLane
        consumerLane = [string]$RequestContext.Event.consumerLane
        status = [string]$RequestContext.Event.targetStatus
        notification = [string]$RequestContext.Event.notification
        publishedSha = $Sha
        publishedOn = $PublishedOn
        productPr = $ProductPrUrl
        workflowRun = $WorkflowRunUrl
        evidence = [ordered]@{
            planPath = [string]$RequestContext.PlanRelative
            gate = [string]$RequestContext.Request.evidence.gate
            contractValidator = 'scripts/tests/producer-handoff-test.ps1'
        }
        sourceStateFingerprint = $SourceFingerprint
        targetStateFingerprint = $TargetFingerprint
        rebuildCount = $RebuildCount
        resourceClaims = @()
        externalMutationCount = 0
        consumerAction = [string]$RequestContext.Event.consumerAction
    }
}

function Assert-ReceiptDocument {
    param([Parameter(Mandatory)][object]$Receipt, [Parameter(Mandatory)][object]$RequestContext)
    Assert-ExactProperties -Document $Receipt -Expected @(
        'schemaVersion', 'requestId', 'requestPath', 'eventId', 'producerLane', 'consumerLane', 'status',
        'notification', 'publishedSha', 'publishedOn', 'productPr', 'workflowRun', 'evidence',
        'sourceStateFingerprint', 'targetStateFingerprint', 'rebuildCount', 'resourceClaims',
        'externalMutationCount', 'consumerAction'
    ) -Context 'Producer handoff receipt'
    Assert-ExactProperties -Document $Receipt.evidence -Expected @('planPath', 'gate', 'contractValidator') -Context 'Receipt evidence'
    if ($Receipt.schemaVersion -ne 1 -or $Receipt.requestId -ne $RequestContext.Request.requestId -or
            $Receipt.requestPath -ne $RequestContext.RequestRelative -or $Receipt.eventId -ne $RequestContext.Request.eventId) {
        Fail-ProducerHandoff 'Receipt identity does not match the request.'
    }
    if ($Receipt.producerLane -ne $RequestContext.Event.producerLane -or $Receipt.consumerLane -ne $RequestContext.Event.consumerLane -or
            $Receipt.status -ne $RequestContext.Event.targetStatus -or $Receipt.notification -ne $RequestContext.Event.notification -or
            $Receipt.consumerAction -ne $RequestContext.Event.consumerAction) {
        Fail-ProducerHandoff 'Receipt policy fields do not match the allowlisted event.'
    }
    if ($Receipt.evidence.planPath -ne $RequestContext.PlanRelative -or
            $Receipt.evidence.gate -ne $RequestContext.Request.evidence.gate -or
            $Receipt.evidence.contractValidator -ne 'scripts/tests/producer-handoff-test.ps1') {
        Fail-ProducerHandoff 'Receipt evidence does not match the immutable request.'
    }
    Assert-CanonicalSha -Value ([string]$Receipt.publishedSha) -Context 'Receipt publishedSha'
    if ((Get-PublishedDate ([string]$Receipt.publishedOn)) -ne [string]$Receipt.publishedOn) {
        Fail-ProducerHandoff 'Receipt publishedOn must be a canonical UTC date.'
    }
    Assert-GitHubUrl -Value ([string]$Receipt.productPr) -Context 'Receipt productPr' -Kind 'pull'
    Assert-GitHubUrl -Value ([string]$Receipt.workflowRun) -Context 'Receipt workflowRun' -Kind 'actions/runs'
    if ([string]$Receipt.sourceStateFingerprint -notmatch '^[0-9a-f]{64}$' -or
            [string]$Receipt.targetStateFingerprint -notmatch '^[0-9a-f]{64}$') {
        Fail-ProducerHandoff 'Receipt state fingerprints are invalid.'
    }
    if ([int]$Receipt.rebuildCount -lt 0 -or [int]$Receipt.rebuildCount -gt [int]$RequestContext.Policy.maxAutomaticRebuilds) {
        Fail-ProducerHandoff 'Receipt rebuildCount exceeds policy.'
    }
    if (@($Receipt.resourceClaims).Count -ne 0 -or [int]$Receipt.externalMutationCount -ne 0) {
        Fail-ProducerHandoff 'Receipt must contain zero resource claims and external mutations.'
    }
}

function Assert-Ancestry {
    param([Parameter(Mandatory)][string]$Candidate, [Parameter(Mandatory)][string]$Descendant)
    if ($SkipAncestryValidation) { return }
    Assert-CanonicalSha -Value $Descendant -Context 'BaseSha'
    & git -C $RepositoryRoot merge-base --is-ancestor $Candidate $Descendant 2>$null
    if ($LASTEXITCODE -ne 0) { Fail-ProducerHandoff 'Product merge SHA is not in the state PR base ancestry.' }
}

function Emit-Result {
    param([Parameter(Mandatory)][object]$Result)
    if ($Json) { $Result | ConvertTo-Json -Depth 20 -Compress }
    else { [pscustomobject]$Result | Format-List }
}

$policyContext = Get-PolicyContext
$requestContext = Get-RequestContext -Policy $policyContext

if ($Mode -eq 'ValidateRequest') {
    $stateContext = Get-StateContext -RequestContext $requestContext -OverridePath $StatePath
    $status = [string]$stateContext.Trigger.status
    if ($status -notin @($requestContext.Event.allowedFromStatuses) -and $status -ne [string]$requestContext.Event.targetStatus) {
        Fail-ProducerHandoff "Trigger status '$status' cannot be automated."
    }
    Emit-Result ([ordered]@{
        status = 'passed'
        requestId = [string]$requestContext.Request.requestId
        eventId = [string]$requestContext.Request.eventId
        statePath = [string]$stateContext.Relative
        sourceStateFingerprint = [string]$stateContext.Fingerprint
        resourceClaims = 0
        externalMutationCount = 0
    })
    return
}

if ($Mode -eq 'PrepareState') {
    Assert-CanonicalSha -Value $ProductMergeSha -Context 'ProductMergeSha'
    Assert-GitHubUrl -Value $ProductPrUrl -Context 'ProductPrUrl' -Kind 'pull'
    Assert-GitHubUrl -Value $WorkflowRunUrl -Context 'WorkflowRunUrl' -Kind 'actions/runs'
    $publishedOn = Get-PublishedDate $PublishedAt
    $stateContext = Get-StateContext -RequestContext $requestContext -OverridePath $StatePath
    $receiptRelative = Get-ReceiptRelativePath -RequestContext $requestContext -Sha $ProductMergeSha
    $receiptAbsolute = if ($ReceiptPath) { [IO.Path]::GetFullPath($ReceiptPath) } else { Resolve-RepositoryPath $receiptRelative }
    if ([string]$stateContext.Trigger.status -eq [string]$requestContext.Event.targetStatus) {
        if ([string]$stateContext.Trigger.publishedSha -ne $ProductMergeSha -or -not (Test-Path -LiteralPath $receiptAbsolute -PathType Leaf)) {
            Fail-ProducerHandoff 'Target trigger is already READY for a different or incomplete delivery.'
        }
        $existingReceipt = Read-JsonDocument -Path $receiptAbsolute -Context 'Existing producer handoff receipt'
        Assert-ReceiptDocument -Receipt $existingReceipt -RequestContext $requestContext
        Emit-Result ([ordered]@{ status = 'already-prepared'; statePath = $stateContext.Relative; receiptPath = $receiptRelative })
        return
    }
    if ([string]$stateContext.Trigger.status -notin @($requestContext.Event.allowedFromStatuses)) {
        Fail-ProducerHandoff "Trigger status '$($stateContext.Trigger.status)' cannot transition to READY."
    }
    $expected = Apply-ExpectedStateTransition -RequestContext $requestContext -BaseDocument $stateContext.Document -Sha $ProductMergeSha -PublishedOn $publishedOn
    $expectedContextPath = Join-Path ([IO.Path]::GetTempPath()) ('producer-handoff-expected-' + [guid]::NewGuid().ToString('n') + '.json')
    try {
        Write-JsonDocument -Path $expectedContextPath -Document $expected
        $targetContext = Get-StateContext -RequestContext $requestContext -OverridePath $expectedContextPath
    } finally {
        if (Test-Path -LiteralPath $expectedContextPath) { Remove-Item -LiteralPath $expectedContextPath -Force -ErrorAction SilentlyContinue }
    }
    $receipt = New-ReceiptDocument -RequestContext $requestContext -Sha $ProductMergeSha -PublishedOn $publishedOn `
        -SourceFingerprint $stateContext.Fingerprint -TargetFingerprint $targetContext.Fingerprint -RebuildCount 0
    Write-JsonDocument -Path $stateContext.Absolute -Document $expected
    Write-JsonDocument -Path $receiptAbsolute -Document $receipt
    Emit-Result ([ordered]@{
        status = 'prepared'
        requestId = [string]$requestContext.Request.requestId
        eventId = [string]$requestContext.Request.eventId
        statePath = [string]$stateContext.Relative
        receiptPath = $receiptRelative
        branch = "automation/producer-handoff/$(([string]$requestContext.Request.eventId).ToLowerInvariant())/$($ProductMergeSha.Substring(0, 12))"
        sourceStateFingerprint = [string]$stateContext.Fingerprint
        targetStateFingerprint = [string]$targetContext.Fingerprint
    })
    return
}

if ($Mode -eq 'ValidateStatePr') {
    if (-not $BaseStatePath -or -not $HeadStatePath -or -not $ReceiptPath -or -not $ChangedFilesJson -or -not $BaseSha) {
        Fail-ProducerHandoff 'ValidateStatePr requires BaseStatePath, HeadStatePath, ReceiptPath, ChangedFilesJson and BaseSha.'
    }
    $baseContext = Get-StateContext -RequestContext $requestContext -OverridePath $BaseStatePath
    $headContext = Get-StateContext -RequestContext $requestContext -OverridePath $HeadStatePath
    $receipt = Read-JsonDocument -Path $ReceiptPath -Context 'Producer handoff receipt'
    Assert-ReceiptDocument -Receipt $receipt -RequestContext $requestContext
    Assert-Ancestry -Candidate ([string]$receipt.publishedSha) -Descendant $BaseSha
    $receiptRelative = Get-ReceiptRelativePath -RequestContext $requestContext -Sha ([string]$receipt.publishedSha)
    $changedDocument = Read-JsonDocument -Path $ChangedFilesJson -Context 'Changed-files list'
    $changed = @($changedDocument)
    if ($changed.Count -ne 2) { Fail-ProducerHandoff 'State-only PR must contain exactly two changed files.' }
    $normalized = @($changed | ForEach-Object {
        [pscustomobject]@{ path = Normalize-RepositoryPath -Path ([string]$_.filename) -Context 'Changed filename'; status = ([string]$_.status).ToLowerInvariant() }
    })
    $stateChange = @($normalized | Where-Object { $_.path -eq [string]$requestContext.Event.statePath -and $_.status -eq 'modified' })
    $receiptChange = @($normalized | Where-Object { $_.path -eq $receiptRelative -and $_.status -eq 'added' })
    if ($stateChange.Count -ne 1 -or $receiptChange.Count -ne 1) { Fail-ProducerHandoff 'State-only PR changed an unexpected path or status.' }
    if ($baseContext.Fingerprint -ne [string]$receipt.sourceStateFingerprint) {
        Fail-ProducerHandoff 'Target trigger changed after the automation branch was prepared; mark the request STALE.'
    }
    $expected = Apply-ExpectedStateTransition -RequestContext $requestContext -BaseDocument $baseContext.Document `
        -Sha ([string]$receipt.publishedSha) -PublishedOn ([string]$receipt.publishedOn)
    $expectedFingerprint = Get-DocumentFingerprint $expected
    $headFingerprint = Get-DocumentFingerprint $headContext.Document
    if ($expectedFingerprint -ne $headFingerprint) {
        $expectedTargetPath = Join-Path ([IO.Path]::GetTempPath()) ('producer-handoff-target-' + [guid]::NewGuid().ToString('n') + '.json')
        try {
            Write-JsonDocument -Path $expectedTargetPath -Document $expected
            $expectedTargetContext = Get-StateContext -RequestContext $requestContext -OverridePath $expectedTargetPath
        } finally {
            if (Test-Path -LiteralPath $expectedTargetPath) { Remove-Item -LiteralPath $expectedTargetPath -Force -ErrorAction SilentlyContinue }
        }
        if ($expectedTargetContext.Fingerprint -eq $headContext.Fingerprint -and
                [int]$receipt.rebuildCount -lt [int]$policyContext.maxAutomaticRebuilds) {
            Emit-Result ([ordered]@{ status = 'rebuild-required'; receiptPath = $receiptRelative; statePath = [string]$requestContext.Event.statePath })
            return
        }
        Fail-ProducerHandoff 'State-only PR content does not equal the policy-derived state transition.'
    }
    if ($headContext.Fingerprint -ne [string]$receipt.targetStateFingerprint) {
        Fail-ProducerHandoff 'Receipt targetStateFingerprint does not match the PR state.'
    }
    Emit-Result ([ordered]@{ status = 'passed'; receiptPath = $receiptRelative; statePath = [string]$requestContext.Event.statePath })
    return
}

if ($Mode -eq 'RebuildState') {
    if (-not $BaseStatePath -or -not $HeadStatePath -or -not $ReceiptPath) {
        Fail-ProducerHandoff 'RebuildState requires BaseStatePath, HeadStatePath and ReceiptPath.'
    }
    $baseContext = Get-StateContext -RequestContext $requestContext -OverridePath $BaseStatePath
    $receipt = Read-JsonDocument -Path $ReceiptPath -Context 'Producer handoff receipt'
    Assert-ReceiptDocument -Receipt $receipt -RequestContext $requestContext
    if ($baseContext.Fingerprint -ne [string]$receipt.sourceStateFingerprint) {
        Fail-ProducerHandoff 'Target trigger changed; automatic rebuild is forbidden.'
    }
    if ([int]$receipt.rebuildCount -ge [int]$policyContext.maxAutomaticRebuilds) {
        Fail-ProducerHandoff 'Automatic rebuild limit has been reached.'
    }
    $rebuilt = Apply-ExpectedStateTransition -RequestContext $requestContext -BaseDocument $baseContext.Document `
        -Sha ([string]$receipt.publishedSha) -PublishedOn ([string]$receipt.publishedOn)
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ('producer-handoff-rebuild-' + [guid]::NewGuid().ToString('n') + '.json')
    try {
        Write-JsonDocument -Path $temporary -Document $rebuilt
        $rebuiltContext = Get-StateContext -RequestContext $requestContext -OverridePath $temporary
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
    }
    Set-DocumentProperty -Document $receipt -Name 'targetStateFingerprint' -Value $rebuiltContext.Fingerprint
    Set-DocumentProperty -Document $receipt -Name 'rebuildCount' -Value ([int]$receipt.rebuildCount + 1)
    Write-JsonDocument -Path ([IO.Path]::GetFullPath($HeadStatePath)) -Document $rebuilt
    Write-JsonDocument -Path ([IO.Path]::GetFullPath($ReceiptPath)) -Document $receipt
    Emit-Result ([ordered]@{ status = 'rebuilt'; rebuildCount = [int]$receipt.rebuildCount; targetStateFingerprint = $rebuiltContext.Fingerprint })
    return
}

if ($Mode -eq 'Finalize') {
    if (-not $StatePath -or -not $ReceiptPath) { Fail-ProducerHandoff 'Finalize requires StatePath and ReceiptPath.' }
    $stateRelative = Normalize-RepositoryPath -Path $StatePath -Context 'StatePath'
    $receiptRelative = Normalize-RepositoryPath -Path $ReceiptPath -Context 'ReceiptPath'
    if ($stateRelative -cne [string]$requestContext.Event.statePath) {
        Fail-ProducerHandoff 'Finalize state path does not match policy.'
    }
    $stateContext = Get-StateContext -RequestContext $requestContext -OverridePath (Resolve-RepositoryPath $stateRelative)
    $receipt = Read-JsonDocument -Path (Resolve-RepositoryPath $receiptRelative) -Context 'Producer handoff receipt'
    Assert-ReceiptDocument -Receipt $receipt -RequestContext $requestContext
    $expectedReceiptRelative = Get-ReceiptRelativePath -RequestContext $requestContext -Sha ([string]$receipt.publishedSha)
    if ($receiptRelative -cne $expectedReceiptRelative) {
        Fail-ProducerHandoff 'Finalize receipt path does not match its deterministic identity.'
    }
    if ([string]$stateContext.Trigger.status -ne [string]$requestContext.Event.targetStatus -or
            [string]$stateContext.Trigger.publishedSha -ne [string]$receipt.publishedSha -or
            $stateContext.Fingerprint -ne [string]$receipt.targetStateFingerprint) {
        Fail-ProducerHandoff 'Merged producer state does not match the receipt.'
    }
    $delivered = [ordered]@{
        eventId = [string]$receipt.eventId
        notification = [string]$receipt.notification
        producerLane = [string]$receipt.producerLane
        consumerLane = [string]$receipt.consumerLane
        status = 'DELIVERED'
        publishedSha = [string]$receipt.publishedSha
        productPr = [string]$receipt.productPr
        workflowRun = [string]$receipt.workflowRun
        consumerAction = [string]$receipt.consumerAction
        claimsReleased = @()
        blockers = @()
        userDecisionRequired = @()
        mustYieldNow = $false
    }
    $marker = "<!-- mms-producer-handoff:v1:$($receipt.requestId):$($receipt.publishedSha) -->"
    $body = $marker + [Environment]::NewLine + '```json' + [Environment]::NewLine +
        ($delivered | ConvertTo-Json -Depth 12) + [Environment]::NewLine + '```'
    Emit-Result ([ordered]@{
        status = 'ready-to-notify'
        issueTitle = [string]$policyContext.receiptIssueTitle
        marker = $marker
        body = $body
    })
}
