Set-StrictMode -Version Latest

if (-not (Get-Command Write-CoordinationJsonAtomic -ErrorAction SilentlyContinue)) {
    . "$PSScriptRoot\coordination-common.ps1"
}

function Get-ManagedComponentDefinition {
    param(
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][string]$Worktree
    )
    $normalized = [IO.Path]::GetFullPath($Worktree).TrimEnd('\').ToLowerInvariant()
    switch ($Component) {
        'SpringBoot' {
            return [pscustomobject]@{
                Component=$Component; StateField='springBootPid'; ReceiptField='springBootOwnershipReceiptId'
                Resource="v1/worktree/maven-target/root/$normalized"; Port=8080; Label='Spring Boot'
            }
        }
        'Dispatcher' {
            return [pscustomobject]@{
                Component=$Component; StateField='dispatcherPid'; ReceiptField='dispatcherOwnershipReceiptId'
                Resource="v1/worktree/maven-target/dispatcher/$normalized"; Port=8091; Label='AI Dispatcher'
            }
        }
        default {
            return [pscustomobject]@{
                Component=$Component; StateField='ngrokPid'; ReceiptField='ngrokOwnershipReceiptId'
                Resource="v1/worktree/managed-process/ngrok/$normalized"; Port=4040; Label='ngrok'
            }
        }
    }
}

function Get-ManagedProcessQueryResult {
    param([Parameter(Mandatory)][int]$ProcessId)
    $searcher = $null
    $items = @()
    try {
        $query = "SELECT ProcessId,ParentProcessId,Name,ExecutablePath,CommandLine,CreationDate FROM Win32_Process WHERE ProcessId=$ProcessId"
        $searcher = [System.Management.ManagementObjectSearcher]::new($query)
        $searcher.Options.Timeout = [TimeSpan]::FromSeconds(3)
        $items = @($searcher.Get())
        if ($items.Count -eq 0) { return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null} }
        $item = $items[0]
        $startedAt = [Management.ManagementDateTimeConverter]::ToDateTime([string]$item.CreationDate).ToUniversalTime()
        return [pscustomobject]@{
            Outcome='READY'
            Snapshot=[pscustomobject]@{
                ProcessId=[int]$item.ProcessId; ParentProcessId=[int]$item.ParentProcessId
                Name=[string]$item.Name; ExecutablePath=[string]$item.ExecutablePath
                CommandLine=[string]$item.CommandLine; StartedAtUtc=$startedAt
            }
        }
    } catch {
        $managementReason = $_.Exception.Message
        $native = $null
        try {
            $native = [Diagnostics.Process]::GetProcessById($ProcessId)
            return [pscustomobject]@{
                Outcome='READY'
                Snapshot=[pscustomobject]@{
                    ProcessId=$native.Id;ParentProcessId=0;Name="$($native.ProcessName).exe"
                    ExecutablePath=$null;CommandLine=$null;StartedAtUtc=$native.StartTime.ToUniversalTime()
                    QueryKind='LIMITED_NATIVE';ManagementReason=$managementReason
                }
            }
        } catch [ArgumentException] {
            return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
        } catch {
            return [pscustomobject]@{Outcome='UNKNOWN';Snapshot=$null;Reason="$managementReason; native query: $($_.Exception.Message)"}
        } finally {
            if ($native) { $native.Dispose() }
        }
    } finally {
        foreach ($item in $items) { if ($item -is [IDisposable]) { $item.Dispose() } }
        if ($searcher) { $searcher.Dispose() }
    }
}

function Get-ManagedCoordinationOperations {
    param([Parameter(Mandatory)][string]$StateRoot)
    $directory = Join-Path $StateRoot 'operations'
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { return @() }
    $documents = [Collections.Generic.List[object]]::new()
    foreach ($file in @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -File -ErrorAction Stop)) {
        try {
            $document = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
        } catch {
            throw "Coordination operation evidence is invalid: $($file.Name)"
        }
        $document | Add-Member -NotePropertyName EvidencePath -NotePropertyValue $file.FullName -Force
        $documents.Add($document)
    }
    return ,$documents.ToArray()
}

function Test-ManagedProcessCommand {
    param(
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [switch]$TrustedReceipt,
        [switch]$ExactActiveCoordinationProof
    )
    $rawCommand = ([string]$Snapshot.CommandLine).ToLowerInvariant()
    $command = $rawCommand.Replace('/', '\')
    $worktreeToken = [IO.Path]::GetFullPath($Worktree).TrimEnd('\').ToLowerInvariant()
    $name = ([string]$Snapshot.Name).ToLowerInvariant()
    if ($Component -eq 'Ngrok') {
        return $TrustedReceipt -and $name -like 'ngrok*' -and $rawCommand -match 'http://localhost:8080'
    }
    if ([string]::IsNullOrWhiteSpace($rawCommand)) {
        return $ExactActiveCoordinationProof -and $name -in @('powershell.exe','pwsh.exe','cmd.exe','java.exe')
    }
    if (-not $command.Contains($worktreeToken) -or $command -notmatch 'coordinated-maven-run\.ps1') { return $false }
    if ($Component -eq 'Dispatcher') { return $command -match '(?i)-application\s+["'']?dispatcher(?:\s|$)' }
    return $command -match '(?i)-application\s+["'']?root(?:\s|$)'
}

function Test-ManagedEvidenceStartTime {
    param([Parameter(Mandatory)]$Snapshot, [Parameter(Mandatory)][string]$EvidenceStartedAt)
    $evidenceTime = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($EvidenceStartedAt, [ref]$evidenceTime)) { return $false }
    $processTime = [datetimeoffset]$Snapshot.StartedAtUtc
    $delta = ($evidenceTime.ToUniversalTime() - $processTime.ToUniversalTime()).TotalSeconds
    return $delta -ge -5 -and $delta -le 120
}

function Read-ManagedOwnershipReceipt {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)]$Definition,
        [Parameter(Mandatory)][string]$StateRoot,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][int]$ProcessId
    )
    $property = $State.PSObject.Properties[$Definition.ReceiptField]
    if (-not $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) { return $null }
    $receiptId = [string]$property.Value
    if ($receiptId -notmatch '^[a-f0-9-]{36}$') { throw 'Managed process ownership receipt id is invalid.' }
    $path = Join-Path (Join-Path $StateRoot 'receipts') "$receiptId.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Managed process ownership receipt is missing.' }
    try { $receipt = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw 'Managed process ownership receipt is invalid.' }
    $normalized = [IO.Path]::GetFullPath($Worktree).TrimEnd('\')
    if ($receipt.action -ne 'managed-process-start' -or $receipt.component -ne $Definition.Component -or
            [int]$receipt.processId -ne $ProcessId -or
            -not [string]::Equals([string]$receipt.worktree, $normalized, [StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals([string]$receipt.resource, $Definition.Resource, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Managed process ownership receipt does not match the requested worktree and component.'
    }
    $receipt | Add-Member -NotePropertyName EvidencePath -NotePropertyValue $path -Force
    return $receipt
}

function New-ManagedProcessOwnershipReceipt {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)][int]$ProcessId,
        [string]$StateRoot = (Get-CoordinationDefaultRoot)
    )
    $query = Get-ManagedProcessQueryResult -ProcessId $ProcessId
    if ($query.Outcome -ne 'READY') { throw 'Managed process start time could not be recorded; ownership is not publishable.' }
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    if ($Component -eq 'Ngrok' -and -not (Test-ManagedProcessCommand -Snapshot $query.Snapshot `
            -Worktree $Worktree -Component $Component -TrustedReceipt)) {
        throw 'The launched ngrok command did not match the repository lifecycle contract.'
    }
    $receiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
        operationId=$receiptId;outcome='READY';action='managed-process-start';component=$Component
        processId=$ProcessId;processStartedAt=([datetimeoffset]$query.Snapshot.StartedAtUtc).ToString('o')
        worktree=[IO.Path]::GetFullPath($Worktree).TrimEnd('\');resource=$definition.Resource
        disposition='managed-runtime; evidence-retained'
    })
    return $receiptId
}

function Get-ManagedProcessOwnershipProof {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [Parameter(Mandatory)]$State,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id },
        [AllowNull()][object[]]$OperationDocuments
    )
    $definition = Get-ManagedComponentDefinition -Component $Component -Worktree $Worktree
    $trackedProperty = $State.PSObject.Properties[$definition.StateField]
    if (-not $trackedProperty -or -not $trackedProperty.Value) {
        return [pscustomobject]@{Outcome='READY';Disposition='ALREADY_STOPPED';Definition=$definition;ProcessId=$null;ProcessPresent=$false}
    }
    $processId = [int]$trackedProperty.Value
    if ($processId -le 0) { return [pscustomobject]@{Outcome='BLOCKED';Reason='Tracked PID is invalid.';Definition=$definition} }

    try { $receipt = Read-ManagedOwnershipReceipt -State $State -Definition $definition -StateRoot $StateRoot -Worktree $Worktree -ProcessId $processId }
    catch { return [pscustomobject]@{Outcome='BLOCKED';Reason=$_.Exception.Message;Definition=$definition;ProcessId=$processId} }
    try { $operations = if ($null -ne $OperationDocuments) { @($OperationDocuments) } else { @(Get-ManagedCoordinationOperations -StateRoot $StateRoot) } }
    catch { return [pscustomobject]@{Outcome='BLOCKED';Reason=$_.Exception.Message;Definition=$definition;ProcessId=$processId} }

    $matchingOperations = @($operations | Where-Object {
        [int]$_.ownerPid -eq $processId -and @($_.resources) -contains $definition.Resource -and $_.status -in @('ACTIVE','RELEASED')
    })
    $query = & $ProcessQuery $processId
    if (-not $query -or $query.Outcome -eq 'UNKNOWN') {
        $queryReason = if ($query -and $query.PSObject.Properties['Reason']) { [string]$query.Reason } else { 'no process query result' }
        return [pscustomobject]@{Outcome='BLOCKED';Reason="The tracked process could not be inspected: $queryReason";Definition=$definition;ProcessId=$processId}
    }

    $hasEvidence = $matchingOperations.Count -eq 1 -or $null -ne $receipt
    if ($query.Outcome -eq 'NOT_FOUND') {
        if (-not $hasEvidence) {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='The stale PID has no matching lifecycle or coordination evidence.';Definition=$definition;ProcessId=$processId}
        }
        $operation = if ($matchingOperations.Count -eq 1) { $matchingOperations[0] } else { $null }
        return [pscustomobject]@{Outcome='READY';Disposition='PROVEN_NOT_RUNNING';Definition=$definition;ProcessId=$processId;ProcessPresent=$false;Operation=$operation;Receipt=$receipt}
    }

    $snapshot = $query.Snapshot
    if ([int]$snapshot.ProcessId -ne $processId) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='Process inspection returned a different PID.';Definition=$definition;ProcessId=$processId}
    }
    if ($matchingOperations.Count -gt 1) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='Multiple coordination operations claim the same managed PID.';Definition=$definition;ProcessId=$processId}
    }
    $operation = if ($matchingOperations.Count -eq 1) { $matchingOperations[0] } else { $null }
    $trustedReceipt = $null -ne $receipt
    if (-not $operation -and -not $trustedReceipt) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='No exact worktree coordination resource proves ownership.';Definition=$definition;ProcessId=$processId}
    }
    $startedAt = if ($operation) { [string]$operation.startedAt } else { [string]$receipt.processStartedAt }
    if (-not (Test-ManagedEvidenceStartTime -Snapshot $snapshot -EvidenceStartedAt $startedAt)) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='PID creation time does not match ownership evidence; PID reuse is possible.';Definition=$definition;ProcessId=$processId}
    }
    $exactActiveOperation = $operation -and $operation.status -eq 'ACTIVE'
    if (-not (Test-ManagedProcessCommand -Snapshot $snapshot -Worktree $Worktree -Component $Component `
            -TrustedReceipt:$trustedReceipt -ExactActiveCoordinationProof:$exactActiveOperation)) {
        return [pscustomobject]@{Outcome='BLOCKED';Reason='Process command does not match the managed component and exact worktree.';Definition=$definition;ProcessId=$processId}
    }

    foreach ($candidate in @($operations | Where-Object {
        $_.status -eq 'ACTIVE' -and @($_.resources) -contains $definition.Resource -and [int]$_.ownerPid -ne $processId
    })) {
        $other = & $ProcessQuery ([int]$candidate.ownerPid)
        if (-not $other -or $other.Outcome -eq 'UNKNOWN') {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='Another active owner on the exact resource cannot be classified.';Definition=$definition;ProcessId=$processId}
        }
        if ($other.Outcome -eq 'READY' -and (Test-ManagedEvidenceStartTime -Snapshot $other.Snapshot -EvidenceStartedAt ([string]$candidate.startedAt))) {
            return [pscustomobject]@{Outcome='BLOCKED';Reason='Another active session legitimately owns the exact worktree resource.';Definition=$definition;ProcessId=$processId}
        }
    }
    return [pscustomobject]@{Outcome='READY';Disposition='OWNED_RUNNING';Definition=$definition;ProcessId=$processId;ProcessPresent=$true;Snapshot=$snapshot;Operation=$operation;Receipt=$receipt}
}

function Update-ManagedDevStateAtomic {
    param(
        [Parameter(Mandatory)][string]$StateFile,
        [Parameter(Mandatory)]$Definition
    )
    $guard = New-CoordinationMutex "service-state/$StateFile"
    $held = $false
    try {
        $held = Wait-CoordinationMutex -Mutex $guard -Deadline ([datetime]::UtcNow.AddSeconds(10))
        if (-not $held) { throw 'Development state is BUSY; component ownership was not changed.' }
        $state = [IO.File]::ReadAllText($StateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        $merged = @{}
        foreach ($property in $state.PSObject.Properties) { $merged[$property.Name] = $property.Value }
        $merged[$Definition.StateField] = $null
        $merged[$Definition.ReceiptField] = $null
        if ($Definition.Component -eq 'Ngrok') { $merged['ngrokUrl'] = $null }
        if ($Definition.Component -eq 'Dispatcher') { $merged['dispatcherArmed'] = $false }
        Write-CoordinationJsonAtomic -Path $StateFile -Document $merged
    } finally {
        if ($held) { $guard.ReleaseMutex() }
        $guard.Dispose()
    }
}

function Complete-ManagedComponentStop {
    param(
        [Parameter(Mandatory)]$Proof,
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][string]$StateFile,
        [string]$StateRoot = (Get-CoordinationDefaultRoot)
    )
    if ($Proof.Operation -and $Proof.Operation.EvidencePath -and
            (Test-Path -LiteralPath $Proof.Operation.EvidencePath -PathType Leaf)) {
        $manifest = [IO.File]::ReadAllText($Proof.Operation.EvidencePath, [Text.Encoding]::UTF8) | ConvertFrom-Json
        if ([int]$manifest.ownerPid -ne [int]$Proof.ProcessId -or
                -not (@($manifest.resources) -contains $Proof.Definition.Resource)) {
            throw 'Coordination ownership changed before stop completion; state was not cleared.'
        }
        if ($manifest.status -eq 'ACTIVE') {
            $manifest.status = 'RELEASED'
            $manifest | Add-Member -NotePropertyName releasedAt -NotePropertyValue ([datetime]::UtcNow.ToString('o')) -Force
            $manifest | Add-Member -NotePropertyName releaseReason -NotePropertyValue 'verified-managed-process-stop' -Force
            Write-CoordinationJsonAtomic -Path $Proof.Operation.EvidencePath -Document $manifest
        }
    }
    Update-ManagedDevStateAtomic -StateFile $StateFile -Definition $Proof.Definition
    $receiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $StateRoot -Receipt ([ordered]@{
        operationId=$receiptId;outcome='READY';action='managed-process-stop';component=$Proof.Definition.Component
        processId=$Proof.ProcessId;worktree=[IO.Path]::GetFullPath($Worktree).TrimEnd('\')
        resource=$Proof.Definition.Resource;disposition='evidence-retained; component-state-cleared'
    })
    return $receiptId
}

function Invoke-ManagedComponentStop {
    param(
        [Parameter(Mandatory)][string]$Worktree,
        [Parameter(Mandatory)][ValidateSet('SpringBoot','Dispatcher','Ngrok')][string]$Component,
        [string]$StateRoot = (Get-CoordinationDefaultRoot),
        [scriptblock]$ProcessQuery = { param($id) Get-ManagedProcessQueryResult -ProcessId $id },
        [AllowNull()][object[]]$OperationDocuments,
        [Parameter(Mandatory)][scriptblock]$StopAdapter
    )
    $stateFile = Join-Path $Worktree 'scripts\.dev-state.json'
    $state = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $proof = Get-ManagedProcessOwnershipProof -Worktree $Worktree -Component $Component -State $state `
        -StateRoot $StateRoot -ProcessQuery $ProcessQuery -OperationDocuments $OperationDocuments
    if ($proof.Outcome -ne 'READY') { return $proof }
    if ($proof.Disposition -eq 'ALREADY_STOPPED') {
        return [pscustomobject]@{Outcome='READY';Disposition='ALREADY_STOPPED';Component=$Component;Changed=$false}
    }
    if ($proof.ProcessPresent) {
        $stopResult = & $StopAdapter $proof
        if (-not $stopResult -or -not $stopResult.Success) {
            return [pscustomobject]@{Outcome='RECOVERY_REQUIRED';Reason="$Component stop verification failed; ownership was retained.";Proof=$proof}
        }
    }
    $receiptId = Complete-ManagedComponentStop -Proof $proof -Worktree $Worktree -StateFile $stateFile -StateRoot $StateRoot
    return [pscustomobject]@{Outcome='READY';Disposition=$proof.Disposition;Component=$Component;Changed=$true;ReceiptId=$receiptId;Proof=$proof}
}

