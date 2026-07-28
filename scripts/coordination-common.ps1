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
        [string]$StateRoot
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
            Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $StateRoot $OperationId) -Document ([ordered]@{
                schemaVersion = $script:CoordinationSchemaVersion; operationId = $OperationId; status = 'ACTIVE'
                ownerPid = $PID; startedAt = [datetime]::UtcNow.ToString('o')
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
    return Join-Path (Join-Path $StateRoot 'operations') "$OperationId.json"
}

function Get-CoordinationOperationManifest {
    param([Parameter(Mandatory)][string]$StateRoot, [Parameter(Mandatory)][string]$OperationId)
    $path = Get-CoordinationOperationPath $StateRoot $OperationId
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { $manifest = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw "Operation manifest $OperationId is invalid; reconcile is BLOCKED." }
    if ($manifest.status -eq 'ACTIVE' -and -not (Get-Process -Id ([int]$manifest.ownerPid) -ErrorAction SilentlyContinue)) {
        $manifest.status = 'ABANDONED'
        $manifest | Add-Member -NotePropertyName abandonedReason -NotePropertyValue 'owner-pid-not-running' -Force
    }
    return $manifest
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
                catch [IO.IOException] { Start-Sleep -Milliseconds 10 }
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
