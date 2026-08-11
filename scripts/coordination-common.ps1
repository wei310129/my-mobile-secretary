Set-StrictMode -Version Latest

$script:CoordinationSchemaVersion = 1
$script:CoordinationExitCodes = @{
    READY = 0; DEGRADED = 10; BUSY = 20; RECOVERY_REQUIRED = 30; BLOCKED = 40; FAILED = 50; INVALID = 64
}
$script:CoordinationRank = @{
    'machine/docker-daemon' = 10; 'machine/docker-capacity' = 20; 'machine/port' = 30
    'machine/ngrok-runtime' = 30; 'machine/line-webhook' = 30; 'repo/git-common' = 40
    'repo/flyway-sequence' = 40; 'worktree/source' = 50; 'worktree/git-index' = 50
    'worktree/maven-target' = 50; 'environment' = 60; 'compose' = 70; 'data' = 80
    'service-state' = 90; 'service-log' = 90
}

function Get-CoordinationHash {
    param([Parameter(Mandatory)][string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    try { return ([Security.Cryptography.SHA256]::Create().ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '' }
    finally { $bytes = $null }
}

function ConvertTo-CoordinationCanonicalProcessStartTime {
    param([Parameter(Mandatory)][datetimeoffset]$Value)
    $utc = $Value.ToUniversalTime()
    return $utc.AddTicks(-($utc.Ticks % 10L))
}

function Format-CoordinationCanonicalProcessStartTime {
    param([Parameter(Mandatory)][datetimeoffset]$Value)
    return (ConvertTo-CoordinationCanonicalProcessStartTime -Value $Value).ToString(
        "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", [Globalization.CultureInfo]::InvariantCulture)
}

function Test-CoordinationCanonicalProcessStartTimeEqual {
    param(
        [Parameter(Mandatory)][datetimeoffset]$Left,
        [Parameter(Mandatory)][datetimeoffset]$Right
    )
    return (ConvertTo-CoordinationCanonicalProcessStartTime -Value $Left).Ticks -eq
        (ConvertTo-CoordinationCanonicalProcessStartTime -Value $Right).Ticks
}

function Test-CoordinationOperationId {
    param([AllowNull()]$Value)
    if ($Value -isnot [string]) { return $false }
    $operationId = [string]$Value
    if ($operationId.Length -lt 1 -or $operationId.Length -gt 128 -or $operationId.Contains('..')) { return $false }
    return $operationId -match '^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9])?$'
}

function Assert-CoordinationOperationId {
    param([AllowNull()]$Value)
    if (-not (Test-CoordinationOperationId -Value $Value)) {
        throw 'Coordination operation id violates the bounded path-safe contract.'
    }
    return [string]$Value
}

function Get-CoordinationProcessIdentityQueryResult {
    param([Parameter(Mandatory)][int]$ProcessId)
    $process = $null
    try {
        $process = [Diagnostics.Process]::GetProcessById($ProcessId)
        return [pscustomobject]@{
            Outcome='READY'
            Snapshot=[pscustomobject]@{
                ProcessId=$ProcessId
                StartedAtUtc=$process.StartTime.ToUniversalTime()
                QueryKind='COORDINATION_PROCESS_EXACT'
            }
        }
    } catch [ArgumentException] {
        return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null;ReasonCode='OWNER_PROCESS_NOT_FOUND'}
    } catch [UnauthorizedAccessException] {
        return [pscustomobject]@{Outcome='CALLER_ACCESS_DENIED';Snapshot=$null;ReasonCode='OWNER_PROCESS_QUERY_ACCESS_DENIED'}
    } catch [ComponentModel.Win32Exception] {
        $reason = if ([int]$_.Exception.NativeErrorCode -eq 5) {
            'OWNER_PROCESS_QUERY_ACCESS_DENIED'
        } else { 'OWNER_PROCESS_IDENTITY_UNAVAILABLE' }
        $outcome = if ($reason -eq 'OWNER_PROCESS_QUERY_ACCESS_DENIED') { 'CALLER_ACCESS_DENIED' } else { 'UNKNOWN' }
        return [pscustomobject]@{Outcome=$outcome;Snapshot=$null;ReasonCode=$reason}
    } catch {
        return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;ReasonCode='OWNER_PROCESS_IDENTITY_UNAVAILABLE'}
    } finally {
        if ($process) { $process.Dispose() }
    }
}

function ConvertTo-CoordinationOperationManifest {
    param([Parameter(Mandatory)]$Manifest)
    if ($Manifest -isnot [pscustomobject]) { throw 'Coordination operation manifest has an invalid document shape.' }
    foreach ($name in @('schemaVersion','operationId','status','ownerPid','startedAt','resources')) {
        if (-not $Manifest.PSObject.Properties[$name]) {
            throw 'Coordination operation manifest is missing required typed evidence.'
        }
    }
    $integerTypes = @([byte],[sbyte],[int16],[uint16],[int32],[uint32],[int64],[uint64])
    if ($Manifest.schemaVersion.GetType() -notin $integerTypes -or [int]$Manifest.schemaVersion -ne 1) {
        throw 'Coordination operation manifest has an unsupported schema.'
    }
    if (-not (Test-CoordinationOperationId -Value $Manifest.operationId)) {
        throw 'Coordination operation manifest has an invalid operation id.'
    }
    if ($Manifest.status -isnot [string] -or [string]$Manifest.status -notin @('ACTIVE','RELEASED','ABANDONED')) {
        throw 'Coordination operation manifest has an invalid status.'
    }
    if ($null -eq $Manifest.ownerPid -or $Manifest.ownerPid.GetType() -notin $integerTypes -or
            [long]$Manifest.ownerPid -le 0 -or [long]$Manifest.ownerPid -gt [int]::MaxValue) {
        throw 'Coordination operation manifest has an invalid owner PID.'
    }
    $publishedAt = [datetimeoffset]::MinValue
    if ($Manifest.startedAt -isnot [string] -or
            -not [datetimeoffset]::TryParse([string]$Manifest.startedAt, [ref]$publishedAt)) {
        throw 'Coordination operation manifest has invalid publication time evidence.'
    }
    if ($Manifest.resources -is [string] -or $Manifest.resources -isnot [Collections.IEnumerable]) {
        throw 'Coordination operation manifest has an invalid resource set.'
    }
    $resources = @($Manifest.resources)
    if ($resources.Count -eq 0 -or @($resources | Where-Object {
            $_ -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$_) -or [string]$_ -notmatch '^v1/'
        }).Count -gt 0 -or @($resources | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
        throw 'Coordination operation manifest has invalid canonical resources.'
    }

    $identityProperty = $Manifest.PSObject.Properties['ownerProcessIdentity']
    $ownerStartedAt = $null
    $legacy = -not $identityProperty
    if ($identityProperty) {
        $identity = $identityProperty.Value
        if ($identity -isnot [pscustomobject]) { throw 'Coordination owner identity has an invalid document shape.' }
        foreach ($name in @('schema','processId','startedAt','canonicalPrecision')) {
            if (-not $identity.PSObject.Properties[$name]) { throw 'Coordination owner identity is incomplete.' }
        }
        if ($identity.schema -isnot [string] -or [string]$identity.schema -ne 'MMS_COORDINATION_OWNER_V1' -or
                $identity.canonicalPrecision -isnot [string] -or
                [string]$identity.canonicalPrecision -ne 'UTC_MICROSECOND_TRUNCATED' -or
                $null -eq $identity.processId -or $identity.processId.GetType() -notin $integerTypes -or
                [int]$identity.processId -ne [int]$Manifest.ownerPid) {
            throw 'Coordination owner identity does not match the typed operation owner.'
        }
        $parsedOwnerStart = [datetimeoffset]::MinValue
        if ($identity.startedAt -isnot [string] -or
                -not [datetimeoffset]::TryParse([string]$identity.startedAt, [ref]$parsedOwnerStart) -or
                [string]$identity.startedAt -ne (Format-CoordinationCanonicalProcessStartTime -Value $parsedOwnerStart) -or
                $parsedOwnerStart.ToUniversalTime() -gt $publishedAt.ToUniversalTime()) {
            throw 'Coordination owner identity has invalid canonical start evidence.'
        }
        $ownerStartedAt = $parsedOwnerStart
    }
    $reconcileProperty = $Manifest.PSObject.Properties['reconcileEvidence']
    if ($reconcileProperty) {
        $evidence = $reconcileProperty.Value
        if ($evidence -isnot [pscustomobject]) { throw 'Coordination reconcile evidence has an invalid document shape.' }
        foreach ($name in @('schema','classification','reasonCode','observedAt','disposition')) {
            if (-not $evidence.PSObject.Properties[$name] -or $evidence.$name -isnot [string] -or
                    [string]::IsNullOrWhiteSpace([string]$evidence.$name)) {
                throw 'Coordination reconcile evidence is incomplete.'
            }
        }
        $reconcileObservedAt = [datetimeoffset]::MinValue
        if ([string]$evidence.schema -ne 'MMS_COORDINATION_RECONCILE_V1' -or
                [string]$evidence.classification -notin @('STALE_DEAD','STALE_PID_REUSED') -or
                [string]$evidence.disposition -ne 'evidence-retained; exactly-once' -or
                -not [datetimeoffset]::TryParse([string]$evidence.observedAt, [ref]$reconcileObservedAt) -or
                $reconcileObservedAt.ToUniversalTime() -lt $publishedAt.ToUniversalTime() -or
                [string]$Manifest.status -ne 'ABANDONED') {
            throw 'Coordination reconcile evidence violates the typed terminal contract.'
        }
    }
    return [pscustomobject]@{
        Document=$Manifest;OperationId=[string]$Manifest.operationId;Status=[string]$Manifest.status
        OwnerPid=[int]$Manifest.ownerPid;PublishedAt=$publishedAt;Resources=$resources
        IsLegacy=$legacy;OwnerProcessStartedAt=$ownerStartedAt
    }
}

function Get-CoordinationOperationOwnerClassification {
    param(
        [Parameter(Mandatory)]$Manifest,
        [Parameter(Mandatory)][string]$ExpectedResource,
        [scriptblock]$ProcessQuery = { param($id) Get-CoordinationProcessIdentityQueryResult -ProcessId $id }
    )
    $typed = ConvertTo-CoordinationOperationManifest -Manifest $Manifest
    if ($typed.Status -ne 'ACTIVE') {
        return [pscustomobject]@{State='INACTIVE';ReasonCode='OPERATION_NOT_ACTIVE';Manifest=$Manifest;Typed=$typed}
    }
    if ($typed.Resources -notcontains $ExpectedResource) {
        return [pscustomobject]@{State='OUT_OF_SCOPE';ReasonCode='RESOURCE_MISMATCH';Manifest=$Manifest;Typed=$typed}
    }
    $query = & $ProcessQuery $typed.OwnerPid
    if (-not $query) {
        return [pscustomobject]@{State='UNKNOWN';ReasonCode='OWNER_PROCESS_QUERY_MISSING';Manifest=$Manifest;Typed=$typed}
    }
    if ([string]$query.Outcome -eq 'NOT_FOUND') {
        return [pscustomobject]@{State='STALE_DEAD';ReasonCode='OWNER_PROCESS_NOT_FOUND';Manifest=$Manifest;Typed=$typed}
    }
    if ([string]$query.Outcome -ne 'READY' -or -not $query.Snapshot -or
            -not $query.Snapshot.PSObject.Properties['ProcessId'] -or
            -not $query.Snapshot.PSObject.Properties['StartedAtUtc']) {
        $reason = if ($query.PSObject.Properties['ReasonCode']) { [string]$query.ReasonCode } else { 'OWNER_PROCESS_IDENTITY_UNAVAILABLE' }
        return [pscustomobject]@{State='UNKNOWN';ReasonCode=$reason;Manifest=$Manifest;Typed=$typed}
    }
    $observedPid = 0
    $observedStartedAt = [datetimeoffset]::MinValue
    try {
        $observedPid = [int]$query.Snapshot.ProcessId
        $observedStartedAt = [datetimeoffset]$query.Snapshot.StartedAtUtc
    } catch {
        return [pscustomobject]@{State='UNKNOWN';ReasonCode='OWNER_PROCESS_IDENTITY_INCOMPLETE';Manifest=$Manifest;Typed=$typed}
    }
    if ($observedPid -ne $typed.OwnerPid) {
        return [pscustomobject]@{State='UNKNOWN';ReasonCode='OWNER_PROCESS_PID_MISMATCH';Manifest=$Manifest;Typed=$typed}
    }
    if (-not $typed.IsLegacy) {
        if (Test-CoordinationCanonicalProcessStartTimeEqual -Left $typed.OwnerProcessStartedAt -Right $observedStartedAt) {
            return [pscustomobject]@{State='ACTIVE_EXACT';ReasonCode='TYPED_OWNER_IDENTITY_MATCH';Manifest=$Manifest;Typed=$typed}
        }
        return [pscustomobject]@{State='STALE_PID_REUSED';ReasonCode='OWNER_PROCESS_START_MISMATCH';Manifest=$Manifest;Typed=$typed}
    }
    if ($observedStartedAt.ToUniversalTime() -le $typed.PublishedAt.ToUniversalTime()) {
        return [pscustomobject]@{State='ACTIVE_LEGACY_BOUNDED';ReasonCode='LEGACY_OWNER_PREDATES_PUBLICATION';Manifest=$Manifest;Typed=$typed}
    }
    return [pscustomobject]@{State='STALE_PID_REUSED';ReasonCode='LEGACY_OWNER_STARTED_AFTER_PUBLICATION';Manifest=$Manifest;Typed=$typed}
}

function Get-CoordinationDefaultRoot {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA is unavailable; machine coordination is BLOCKED.' }
    return Join-Path $env:LOCALAPPDATA 'my-mobile-secretary\coordination\v1'
}

function Get-CoordinationRepoRoot {
    param([Parameter(Mandatory)][string]$GitCommonDir)
    return Join-Path (Join-Path (Get-CoordinationDefaultRoot) 'repos') (Get-CoordinationHash ([IO.Path]::GetFullPath($GitCommonDir).ToLowerInvariant()))
}

function New-CoordinationResource {
    param(
        [Parameter(Mandatory)][string]$Type,
        [Parameter(Mandatory)][string]$Key,
        [ValidateSet('Exclusive', 'Shared', 'Counting')][string]$Mode = 'Exclusive',
        [ValidateRange(1, 64)][int]$Capacity = 1
    )
    if (-not $script:CoordinationRank.ContainsKey($Type)) { throw "Unsupported coordination resource type: $Type" }
    $normalizedKey = $Key.Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($normalizedKey)) { throw 'Resource key must not be blank.' }
    [pscustomobject]@{
        Type = $Type; Key = $normalizedKey; Mode = $Mode; Capacity = $Capacity
        Rank = [int]$script:CoordinationRank[$Type]; CanonicalKey = "v1/$Type/$normalizedKey"
    }
}

function Get-CoordinationOrderedResources {
    param([Parameter(Mandatory)][object[]]$Resources)
    $duplicates = @($Resources | Group-Object CanonicalKey | Where-Object { $_.Count -gt 1 })
    if ($duplicates.Count -gt 0) { throw "Duplicate resource declaration: $($duplicates[0].Name)" }
    return @($Resources | Sort-Object @{ Expression = 'Rank'; Ascending = $true }, @{ Expression = 'Type'; Ascending = $true }, @{ Expression = 'Key'; Ascending = $true })
}

function New-CoordinationMutex {
    param([Parameter(Mandatory)][string]$Name)
    try { return [Threading.Mutex]::new($false, "Global\mms-coord-v1-$(Get-CoordinationHash $Name)") }
    catch { throw "Global mutex is unavailable; machine coordination is BLOCKED. $($_.Exception.Message)" }
}

function Wait-CoordinationMutex {
    param([Parameter(Mandatory)][Threading.Mutex]$Mutex, [Parameter(Mandatory)][datetime]$Deadline)
    $remaining = $Deadline - [datetime]::UtcNow
    if ($remaining.TotalMilliseconds -le 0) { return $false }
    try { return $Mutex.WaitOne($remaining) }
    catch [Threading.AbandonedMutexException] { return $true }
}

function Enter-CoordinationResource {
    param([Parameter(Mandatory)]$Resource, [Parameter(Mandatory)][datetime]$Deadline)
    $gate = New-CoordinationMutex "$($Resource.CanonicalKey)/gate"
    $slots = [Collections.Generic.List[Threading.Mutex]]::new()
    $gateHeld = $false
    $completed = $false
    try {
        if (-not (Wait-CoordinationMutex -Mutex $gate -Deadline $Deadline)) { return $null }
        $gateHeld = $true
        if ($Resource.Mode -eq 'Exclusive') {
            for ($i = 0; $i -lt $Resource.Capacity; $i++) {
                $slot = New-CoordinationMutex "$($Resource.CanonicalKey)/slot/$i"
                if (-not (Wait-CoordinationMutex -Mutex $slot -Deadline $Deadline)) { $slot.Dispose(); return $null }
                $slots.Add($slot)
            }
            $completed = $true
            return [pscustomobject]@{ Resource = $Resource; Gate = $gate; GateHeld = $true; Slots = @($slots); Mode = 'Exclusive' }
        }
        while ([datetime]::UtcNow -lt $Deadline) {
            for ($i = 0; $i -lt $Resource.Capacity; $i++) {
                $slot = New-CoordinationMutex "$($Resource.CanonicalKey)/slot/$i"
                $acquired = $false
                try { $acquired = $slot.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired = $true }
                if ($acquired) {
                    $gate.ReleaseMutex(); $gateHeld = $false
                    $completed = $true
                    return [pscustomobject]@{ Resource = $Resource; Gate = $gate; GateHeld = $false; Slots = @($slot); Mode = $Resource.Mode }
                }
                $slot.Dispose()
            }
            $gate.ReleaseMutex(); $gateHeld = $false
            Start-Sleep -Milliseconds 10
            if (-not (Wait-CoordinationMutex -Mutex $gate -Deadline $Deadline)) { return $null }
            $gateHeld = $true
        }
        return $null
    } finally {
        if (-not $completed) {
            for ($index = $slots.Count - 1; $index -ge 0; $index--) { try { $slots[$index].ReleaseMutex() } finally { $slots[$index].Dispose() } }
            if ($gateHeld) { $gate.ReleaseMutex() }
            $gate.Dispose()
        }
    }
}

function Exit-CoordinationLease {
    param([Parameter(Mandatory)]$Lease)
    $leaseSlots = @($Lease.Slots)
    for ($index = $leaseSlots.Count - 1; $index -ge 0; $index--) { try { $leaseSlots[$index].ReleaseMutex() } finally { $leaseSlots[$index].Dispose() } }
    if ($Lease.GateHeld) { try { $Lease.Gate.ReleaseMutex() } finally { $Lease.Gate.Dispose() } }
    elseif ($Lease.Gate) { $Lease.Gate.Dispose() }
}

function Enter-CoordinationOperation {
    param(
        [Parameter(Mandatory)][object[]]$Resources,
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = 30,
        [string]$OperationId = ([guid]::NewGuid().ToString()),
        [string]$StateRoot,
        [scriptblock]$OwnerProcessQuery = { param($id) Get-CoordinationProcessIdentityQueryResult -ProcessId $id }
    )
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    $leases = [Collections.Generic.List[object]]::new()
    try {
        foreach ($resource in (Get-CoordinationOrderedResources $Resources)) {
            $lease = Enter-CoordinationResource -Resource $resource -Deadline $deadline
            if ($null -eq $lease) {
                for ($index = $leases.Count - 1; $index -ge 0; $index--) { Exit-CoordinationLease $leases[$index] }
                return [pscustomobject]@{ Outcome = 'BUSY'; OperationId = $OperationId; Leases = @() }
            }
            $leases.Add($lease)
        }
        $operation = [pscustomobject]@{ Outcome = 'READY'; OperationId = $OperationId; Leases = @($leases); StateRoot = $StateRoot }
        if ($StateRoot) {
            $ownerQuery = & $OwnerProcessQuery $PID
            if (-not $ownerQuery -or [string]$ownerQuery.Outcome -ne 'READY' -or -not $ownerQuery.Snapshot -or
                    [int]$ownerQuery.Snapshot.ProcessId -ne $PID -or -not $ownerQuery.Snapshot.StartedAtUtc) {
                throw 'Coordination operation owner process identity is unavailable; publication is BLOCKED.'
            }
            $publishedAt = [datetimeoffset]::UtcNow
            $ownerStartedAt = [datetimeoffset]$ownerQuery.Snapshot.StartedAtUtc
            Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $StateRoot $OperationId) -Document ([ordered]@{
                schemaVersion = $script:CoordinationSchemaVersion; operationId = $OperationId; status = 'ACTIVE'
                ownerPid = $PID; startedAt = $publishedAt.ToString('o')
                ownerProcessIdentity = [ordered]@{
                    schema='MMS_COORDINATION_OWNER_V1';processId=$PID
                    startedAt=(Format-CoordinationCanonicalProcessStartTime -Value $ownerStartedAt)
                    canonicalPrecision='UTC_MICROSECOND_TRUNCATED'
                }
                resources = @($leases | ForEach-Object { $_.Resource.CanonicalKey })
            })
        }
        return $operation
    } catch {
        for ($index = $leases.Count - 1; $index -ge 0; $index--) { Exit-CoordinationLease $leases[$index] }
        throw
    }
}

function Exit-CoordinationOperation {
    param([Parameter(Mandatory)]$Operation)
    if ($Operation.StateRoot) {
        $path = Get-CoordinationOperationPath $Operation.StateRoot $Operation.OperationId
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $manifest = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json
            $manifest.status = 'RELEASED'
            $manifest | Add-Member -NotePropertyName releasedAt -NotePropertyValue ([datetime]::UtcNow.ToString('o')) -Force
            Write-CoordinationJsonAtomic -Path $path -Document $manifest
        }
    }
    $operationLeases = @($Operation.Leases)
    for ($index = $operationLeases.Count - 1; $index -ge 0; $index--) { Exit-CoordinationLease $operationLeases[$index] }
}

function Get-CoordinationRegistryPath {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][string]$Name)
    return Join-Path (Join-Path $StateRoot 'registry') "$(Get-CoordinationHash $Name).json"
}

function Get-CoordinationOperationPath {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][string]$OperationId)
    $safeOperationId = Assert-CoordinationOperationId -Value $OperationId
    return Join-Path (Join-Path $StateRoot 'operations') "$safeOperationId.json"
}

function Get-CoordinationOperationManifest {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][string]$OperationId)
    $path = Get-CoordinationOperationPath $StateRoot $OperationId
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { $manifest = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw "Operation manifest $OperationId is invalid; reconcile is BLOCKED." }
    ConvertTo-CoordinationOperationManifest -Manifest $manifest | Out-Null
    return $manifest
}

function Resolve-CoordinationStaleOperation {
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][string]$OperationId,
        [Parameter(Mandatory)][string]$ExpectedResource,
        [scriptblock]$ProcessQuery = { param($id) Get-CoordinationProcessIdentityQueryResult -ProcessId $id }
    )
    $path = Get-CoordinationOperationPath $StateRoot $OperationId
    $guard = New-CoordinationMutex "operation-reconcile/$path"
    $held = $false
    try {
        $held = Wait-CoordinationMutex -Mutex $guard -Deadline ([datetime]::UtcNow.AddSeconds(10))
        if (-not $held) { return [pscustomobject]@{Outcome='BUSY';Changed=$false;OperationId=$OperationId} }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            return [pscustomobject]@{Outcome='BLOCKED';Changed=$false;ReasonCode='OPERATION_EVIDENCE_MISSING';OperationId=$OperationId}
        }
        try { $manifest = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
        catch { return [pscustomobject]@{Outcome='BLOCKED';Changed=$false;ReasonCode='OPERATION_EVIDENCE_INVALID';OperationId=$OperationId} }
        try { $typed = ConvertTo-CoordinationOperationManifest -Manifest $manifest }
        catch { return [pscustomobject]@{Outcome='BLOCKED';Changed=$false;ReasonCode='OPERATION_TYPED_EVIDENCE_INVALID';OperationId=$OperationId} }
        if ($typed.Status -in @('ABANDONED','RELEASED')) {
            return [pscustomobject]@{Outcome='READY';Changed=$false;ReasonCode='OPERATION_ALREADY_TERMINAL';OperationId=$OperationId;Status=$typed.Status}
        }
        $classification = Get-CoordinationOperationOwnerClassification -Manifest $manifest `
            -ExpectedResource $ExpectedResource -ProcessQuery $ProcessQuery
        if ($classification.State -notin @('STALE_DEAD','STALE_PID_REUSED')) {
            $outcome = if ($classification.State -eq 'UNKNOWN') { 'BLOCKED' } else { 'BLOCKED' }
            return [pscustomobject]@{Outcome=$outcome;Changed=$false;ReasonCode=$classification.ReasonCode;OperationId=$OperationId}
        }
        $observedAt = [datetimeoffset]::UtcNow
        $manifest.status = 'ABANDONED'
        $manifest | Add-Member -NotePropertyName abandonedAt -NotePropertyValue $observedAt.ToString('o') -Force
        $manifest | Add-Member -NotePropertyName abandonedReason -NotePropertyValue $(if($classification.State -eq 'STALE_DEAD'){'owner-process-not-found'}else{'owner-process-identity-reused'}) -Force
        $manifest | Add-Member -NotePropertyName reconcileEvidence -NotePropertyValue ([ordered]@{
            schema='MMS_COORDINATION_RECONCILE_V1';classification=[string]$classification.State
            reasonCode=[string]$classification.ReasonCode;observedAt=$observedAt.ToString('o')
            disposition='evidence-retained; exactly-once'
        }) -Force
        Write-CoordinationJsonAtomic -Path $path -Document $manifest
        return [pscustomobject]@{Outcome='READY';Changed=$true;ReasonCode=$classification.ReasonCode;OperationId=$OperationId;Status='ABANDONED'}
    } finally {
        if ($held) { $guard.ReleaseMutex() }
        $guard.Dispose()
    }
}

function Read-CoordinationRegistry {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][string]$Name)
    $path = Get-CoordinationRegistryPath $StateRoot $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return [pscustomobject]@{ generation = 0; value = 0 } }
    $raw = $null
    for ($attempt = 0; $attempt -lt 300 -and $null -eq $raw; $attempt++) {
        try { $raw = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) }
        catch [IO.IOException] { Start-Sleep -Milliseconds 10 }
    }
    if ($null -eq $raw) { throw "Registry $Name stayed locked; reconcile is BLOCKED." }
    try { return $raw | ConvertFrom-Json }
    catch { throw "Registry $Name is invalid; reconcile is BLOCKED. $($_.Exception.Message)" }
}

function Invoke-CoordinationAtomicMoveReplace {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination
    )
    if ($env:OS -ne 'Windows_NT') { return $false }
    if (-not ('Mms.Tooling.AtomicFileReplace' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace Mms.Tooling {
    public static class AtomicFileReplace {
        private const uint MOVEFILE_REPLACE_EXISTING = 0x1;
        private const uint MOVEFILE_WRITE_THROUGH = 0x8;

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool MoveFileEx(string existingName, string newName, uint flags);

        public static bool Replace(string source, string destination) {
            return MoveFileEx(source, destination, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
        }
    }
}
'@
    }
    return [Mms.Tooling.AtomicFileReplace]::Replace($Source, $Destination)
}

function Write-CoordinationJsonAtomic {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Document)
    $directory = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory ".$(Split-Path -Leaf $Path).$([guid]::NewGuid()).tmp"
    $previous = "$Path.previous"
    try {
        $json = $Document | ConvertTo-Json -Depth 12
        [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            $replaced = $false
            for ($attempt = 0; $attempt -lt 50 -and -not $replaced; $attempt++) {
                try { [IO.File]::Replace($temporary, $Path, $previous); $replaced = $true }
                catch [IO.IOException] {
                    if (Invoke-CoordinationAtomicMoveReplace -Source $temporary -Destination $Path) {
                        $replaced = $true
                    } else { Start-Sleep -Milliseconds 10 }
                }
            }
            if (-not $replaced) { throw "Atomic registry replace remained locked: $Path" }
        }
        else { [IO.File]::Move($temporary, $Path) }
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force }
        if (Test-Path -LiteralPath $previous -PathType Leaf) { Remove-Item -LiteralPath $previous -Force }
    }
}

function Invoke-CoordinationRegistryCas {
    param(
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][long]$ExpectedGeneration,
        [Parameter(Mandatory)]$NextDocument
    )
    $guard = New-CoordinationMutex "registry/$StateRoot/$Name"
    $held = $false
    try {
        if (-not (Wait-CoordinationMutex -Mutex $guard -Deadline ([datetime]::UtcNow.AddSeconds(30)))) { return [pscustomobject]@{ Applied = $false; Outcome = 'BUSY' } }
        $held = $true
        $current = Read-CoordinationRegistry $StateRoot $Name
        if ([long]$current.generation -ne $ExpectedGeneration) { return [pscustomobject]@{ Applied = $false; Outcome = 'BUSY'; ActualGeneration = [long]$current.generation } }
        $NextDocument.generation = $ExpectedGeneration + 1
        Write-CoordinationJsonAtomic -Path (Get-CoordinationRegistryPath $StateRoot $Name) -Document $NextDocument
        return [pscustomobject]@{ Applied = $true; Outcome = 'READY'; Generation = $ExpectedGeneration + 1 }
    } finally {
        if ($held) { $guard.ReleaseMutex() }
        $guard.Dispose()
    }
}

function Write-CoordinationReceipt {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)]$Receipt)
    if (-not $Receipt.operationId) { throw 'Receipt operationId is required.' }
    $Receipt.schemaVersion = $script:CoordinationSchemaVersion
    Write-CoordinationJsonAtomic -Path (Join-Path (Join-Path $StateRoot 'receipts') "$($Receipt.operationId).json") -Document $Receipt
}

function New-CoordinationServiceLogDirectory {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][string]$EnvironmentId, [Parameter(Mandatory)][long]$Generation)
    $path = Join-Path (Join-Path (Join-Path $StateRoot 'service-logs') (Get-CoordinationHash $EnvironmentId)) $Generation
    [IO.Directory]::CreateDirectory($path) | Out-Null
    return $path
}

function Get-CoordinationDoctorSnapshot {
    param([string]$StateRoot = (Get-CoordinationDefaultRoot))
    $rootExists = Test-Path -LiteralPath $StateRoot -PathType Container
    [pscustomobject]@{
        schemaVersion = $script:CoordinationSchemaVersion
        outcome = 'DEGRADED'
        exitCode = $script:CoordinationExitCodes.DEGRADED
        stateRoot = $StateRoot
        registryPresent = $rootExists
        machineScope = [pscustomobject]@{ status = 'DEGRADED'; reason = 'same-logon Global mutex prototype passed; cross-logon visibility remains unverified and mutations must fail closed when unavailable' }
        externalProbe = 'skipped'
        cleanup = 'read-only; no reconcile mutation'
    }
}

function Resolve-CoordinationCleanupClass {
    param([hashtable]$Labels, [switch]$OwnerProofComplete)
    if ($Labels['com.mms.coordination.class'] -eq 'shared-persistent') { return 'kept-persistent' }
    if ($OwnerProofComplete -and $Labels['com.mms.coordination.class'] -eq 'disposable') { return 'removed' }
    return 'unknown'
}

function Reserve-CoordinationFlywayVersion {
    param([Parameter(Mandatory)][string]$Application, [Parameter(Mandatory)][string]$Version, [ValidateRange(1,60)][int]$TimeoutSeconds = 1)
    $resource = New-CoordinationResource -Type 'repo/flyway-sequence' -Key "$Application/$Version" -Mode Exclusive
    return Enter-CoordinationOperation -Resources @($resource) -TimeoutSeconds $TimeoutSeconds
}

function Enter-CoordinationSourceWriter {
    param([Parameter(Mandatory)][string]$Worktree, [ValidateRange(1,60)][int]$TimeoutSeconds = 1)
    $resource = New-CoordinationResource -Type 'worktree/source' -Key ([IO.Path]::GetFullPath($Worktree)) -Mode Exclusive
    return Enter-CoordinationOperation -Resources @($resource) -TimeoutSeconds $TimeoutSeconds
}
