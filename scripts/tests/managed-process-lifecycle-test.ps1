[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
. (Join-Path $scripts 'coordination-common.ps1')
. (Join-Path $scripts 'coordination-maven.ps1')
. (Join-Path $scripts 'managed-process-lifecycle.ps1')
. (Join-Path $scripts 'project-tool-policy.ps1')

$root = Join-Path (Join-Path $scripts '.coordination-test-state') ([guid]::NewGuid().ToString())
$worktree = Join-Path $root 'project\var\worktrees\calendar-w11'
$otherWorktree = Join-Path $root 'project\var\worktrees\other'
$stateRoot = Join-Path $root 'coordination'
$stateFile = Join-Path $worktree 'scripts\.dev-state.json'
$started = [datetime]::UtcNow.AddMinutes(-5)
$processes = @{}
$stopCalls = [Collections.Generic.List[string]]::new()

function Assert-Managed([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function New-FakeSnapshot([int]$Id, [string]$CommandLine, [datetime]$StartedAt, [string]$Name = 'powershell.exe') {
    return [pscustomobject]@{
        ProcessId=$Id;ParentProcessId=1;Name=$Name;ExecutablePath="C:\fake\$Name"
        CommandLine=$CommandLine;StartedAtUtc=$StartedAt
    }
}

try {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $stateFile)) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $otherWorktree 'scripts')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $otherWorktree '.git'), 'gitdir: fake', [Text.UTF8Encoding]::new($false))

    $springDefinition = Get-ManagedComponentDefinition -Component SpringBoot -Worktree $worktree
    $springOperationId = [guid]::NewGuid().ToString()
    $springOperationPath = Get-CoordinationOperationPath $stateRoot $springOperationId
    $springOperation = [pscustomobject]@{
        schemaVersion=1;operationId=$springOperationId;status='ACTIVE';ownerPid=100
        startedAt=$started.AddSeconds(2).ToString('o');resources=@($springDefinition.Resource)
        EvidencePath=$springOperationPath
    }
    Write-CoordinationJsonAtomic -Path $springOperationPath -Document $springOperation

    $ngrokDefinition = Get-ManagedComponentDefinition -Component Ngrok -Worktree $worktree
    $ngrokReceiptId = [guid]::NewGuid().ToString()
    Write-CoordinationReceipt -StateRoot $stateRoot -Receipt ([ordered]@{
        operationId=$ngrokReceiptId;outcome='READY';action='managed-process-start';component='Ngrok'
        processId=200;processStartedAt=$started.ToString('o');worktree=[IO.Path]::GetFullPath($worktree).TrimEnd('\')
        resource=$ngrokDefinition.Resource;disposition='managed-runtime'
    })
    Write-CoordinationJsonAtomic -Path $stateFile -Document ([ordered]@{
        springBootPid=100;springBootOwnershipReceiptId=$null;dispatcherPid=$null
        ngrokPid=200;ngrokOwnershipReceiptId=$ngrokReceiptId;ngrokUrl='https://example.invalid'
    })

    $processes[100] = New-FakeSnapshot -Id 100 `
        -CommandLine "powershell -File $worktree\scripts\coordinated-maven-run.ps1 -Application root" `
        -StartedAt $started
    $processes[200] = New-FakeSnapshot -Id 200 -Name 'ngrok.exe' `
        -CommandLine 'ngrok http --domain safe.example http://localhost:8080' -StartedAt $started
    $query = {
        param($id)
        if ($processes.ContainsKey([int]$id)) { return [pscustomobject]@{Outcome='READY';Snapshot=$processes[[int]$id]} }
        return [pscustomobject]@{Outcome='NOT_FOUND';Snapshot=$null}
    }

    $initialState = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $lostPortProof = Get-ManagedProcessOwnershipProof -Worktree $worktree -Component SpringBoot `
        -State $initialState -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments @($springOperation)
    Assert-Managed ($lostPortProof.Outcome -eq 'READY' -and $lostPortProof.ProcessPresent) `
        'tracked Spring launcher without a listening port was not proven by command and ownership evidence'

    $springStop = Invoke-ManagedComponentStop -Worktree $worktree -Component SpringBoot -StateRoot $stateRoot `
        -ProcessQuery $query -OperationDocuments @($springOperation) -StopAdapter {
            param($proof);$stopCalls.Add("$($proof.Definition.Component):$($proof.ProcessId)");[pscustomobject]@{Success=$true}
        }
    Assert-Managed ($springStop.Outcome -eq 'READY') 'verified Spring launcher did not stop'
    $afterSpring = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Managed (-not $afterSpring.springBootPid -and $afterSpring.ngrokPid -eq 200) `
        'Spring component state was not atomically cleared before the later ngrok attempt'

    $ngrokStop = Invoke-ManagedComponentStop -Worktree $worktree -Component Ngrok -StateRoot $stateRoot `
        -ProcessQuery $query -OperationDocuments @($springOperation) -StopAdapter {
            param($proof);$stopCalls.Add("$($proof.Definition.Component):$($proof.ProcessId)");[pscustomobject]@{Success=$false}
        }
    Assert-Managed ($ngrokStop.Outcome -eq 'RECOVERY_REQUIRED') `
        ("ngrok verification failure was not retained: {0}" -f ($ngrokStop | ConvertTo-Json -Compress -Depth 6))
    $afterNgrokFailure = [IO.File]::ReadAllText($stateFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Managed (-not $afterNgrokFailure.springBootPid -and $afterNgrokFailure.ngrokPid -eq 200) `
        'ngrok failure restored or retained already-released Spring ownership'

    $secondSpring = Invoke-ManagedComponentStop -Worktree $worktree -Component SpringBoot -StateRoot $stateRoot `
        -ProcessQuery $query -OperationDocuments @($springOperation) -StopAdapter {
            param($proof);$stopCalls.Add('unexpected-repeat');[pscustomobject]@{Success=$true}
        }
    Assert-Managed ($secondSpring.Outcome -eq 'READY' -and -not $secondSpring.Changed -and
            -not ($stopCalls -contains 'unexpected-repeat')) 'repeated stop was not idempotent'

    $crossState = [pscustomobject]@{springBootPid=300;springBootOwnershipReceiptId=$null}
    $crossOperation = [pscustomobject]@{
        operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=300;startedAt=$started.ToString('o')
        resources=@($springDefinition.Resource);EvidencePath=(Join-Path $root 'cross.json')
    }
    $processes[300] = New-FakeSnapshot -Id 300 `
        -CommandLine "powershell -File $otherWorktree\scripts\coordinated-maven-run.ps1 -Application root" `
        -StartedAt $started
    $crossProof = Get-ManagedProcessOwnershipProof -Worktree $worktree -Component SpringBoot -State $crossState `
        -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments @($crossOperation)
    Assert-Managed ($crossProof.Outcome -eq 'BLOCKED') 'another worktree command was accepted as the target owner'

    $reuseState = [pscustomobject]@{springBootPid=400;springBootOwnershipReceiptId=$null}
    $reuseOperation = [pscustomobject]@{
        operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=400;startedAt=$started.ToString('o')
        resources=@($springDefinition.Resource);EvidencePath=(Join-Path $root 'reuse.json')
    }
    $processes[400] = New-FakeSnapshot -Id 400 `
        -CommandLine "powershell -File $worktree\scripts\coordinated-maven-run.ps1 -Application root" `
        -StartedAt $started.AddDays(1)
    $reuseProof = Get-ManagedProcessOwnershipProof -Worktree $worktree -Component SpringBoot -State $reuseState `
        -StateRoot $stateRoot -ProcessQuery $query -OperationDocuments @($reuseOperation)
    Assert-Managed ($reuseProof.Outcome -eq 'BLOCKED') 'PID reuse did not fail closed'

    $staleOperation = [ordered]@{
        schemaVersion=1;operationId=[guid]::NewGuid().ToString();status='ACTIVE';ownerPid=987654
        startedAt=$started.ToString('o');resources=@($springDefinition.Resource)
    }
    Write-CoordinationJsonAtomic -Path (Get-CoordinationOperationPath $stateRoot $staleOperation.operationId) -Document $staleOperation
    $reacquired = Enter-CoordinationOperation -Resources @(
        (New-CoordinationResource -Type 'worktree/maven-target' -Key "root/$worktree" -Mode Exclusive)
    ) -TimeoutSeconds 2 -StateRoot $stateRoot
    Assert-Managed ($reacquired.Outcome -eq 'READY') 'stale manifest was mistaken for a live OS mutex'
    Exit-CoordinationOperation $reacquired
    $reacquiredAgain = Enter-CoordinationOperation -Resources @(
        (New-CoordinationResource -Type 'worktree/maven-target' -Key "root/$worktree" -Mode Exclusive)
    ) -TimeoutSeconds 2 -StateRoot $stateRoot
    Assert-Managed ($reacquiredAgain.Outcome -eq 'READY') 'same worktree could not reacquire the Maven lease'
    Exit-CoordinationOperation $reacquiredAgain

    Assert-RepositoryMavenInvocation -RepoRoot (Split-Path -Parent $scripts) `
        -Arguments @('-Dtest=CalendarIntentDraftConversationServiceTest','test')
    $unsafeRejected = $false
    try { Assert-RepositoryMavenInvocation -RepoRoot (Split-Path -Parent $scripts) -Arguments @('-f','C:\other\pom.xml','test') }
    catch { $unsafeRejected = $true }
    Assert-Managed $unsafeRejected 'cross-project Maven file selection was not rejected'

    [pscustomobject]@{status='passed';assertions=12;liveProcesses='fake';coordinationEvidence='retained'} | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}

