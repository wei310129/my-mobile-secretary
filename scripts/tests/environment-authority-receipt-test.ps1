[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $scriptsRoot 'environment-common.ps1')
$repoRoot = Split-Path -Parent $scriptsRoot
$stateRoot = Join-Path ([IO.Path]::GetTempPath()) ("mms-authority-" + [guid]::NewGuid().ToString('n').Substring(0,8))
$assertions = 0
function Assert-AuthorityTest([bool]$Condition,[string]$Message) { if(-not $Condition){throw $Message};$script:assertions++ }
try {
    $caller = (Get-EnvironmentCallerContext).Kind
    $issued = New-EnvironmentExternalAuthorityReceipt -Provider TDX -OperationClass ROUTE_QUERY `
        -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop `
        -CallerKind $caller -TtlMinutes 5
    $valid = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider TDX `
        -OperationClass ROUTE_QUERY -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest $valid.Ready "valid scoped TDX receipt was rejected: $($valid.Reason)"
    $otherCaller = if($caller -eq 'host'){'sandbox'}else{'host'}
    $wrongCaller = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider TDX `
        -OperationClass ROUTE_QUERY -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $otherCaller
    Assert-AuthorityTest (-not $wrongCaller.Ready) 'receipt for another caller was accepted'
    $mutation = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider BOOKING `
        -OperationClass BOOKING_CREATE -Scope MUTATION -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $mutation.Ready) 'read-only route receipt authorized booking mutation'
    $wrongScope = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider TDX `
        -OperationClass ROUTE_QUERY -Scope MUTATION -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $wrongScope.Ready) 'wrong authority scope was accepted'
    $wrongProvider = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider GOOGLE `
        -OperationClass ROUTE_QUERY -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $wrongProvider.Ready) 'receipt was reused for another provider'
    $wrongOperation = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider LINE `
        -OperationClass CONNECTIVITY_PROBE -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $wrongOperation.Ready) 'receipt was reused for another operation class'
    $receipt = Read-EnvironmentJson -Path $issued.Path
    $receipt.issuer = 'wrong-issuer'
    Write-CoordinationJsonAtomic -Path $issued.Path -Document $receipt
    $wrongIssuer = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $issued.Path -Provider TDX `
        -OperationClass ROUTE_QUERY -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $wrongIssuer.Ready) 'wrong issuer was accepted'
    $fenceReceipt = New-EnvironmentExternalAuthorityReceipt -Provider TDX -OperationClass ROUTE_QUERY `
        -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop -CallerKind $caller
    $fenceDocument = Read-EnvironmentJson -Path $fenceReceipt.Path
    $fenceDocument.worktreeId = 'wrong-worktree-fence'
    $fenceDocument.receiptFingerprint = Get-EnvironmentExternalAuthorityFingerprint -Receipt $fenceDocument
    Write-CoordinationJsonAtomic -Path $fenceReceipt.Path -Document $fenceDocument
    $wrongFence = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $fenceReceipt.Path -Provider TDX `
        -OperationClass ROUTE_QUERY -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $wrongFence.Ready) 'wrong repository/worktree fence was accepted'
    $expired = New-EnvironmentExternalAuthorityReceipt -Provider GOOGLE -OperationClass ROUTE_QUERY `
        -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop `
        -CallerKind $caller -IssuedAt ([datetime]::UtcNow.AddMinutes(-10)) -TtlMinutes 1
    $expiredResult = Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $expired.Path -Provider GOOGLE `
        -OperationClass ROUTE_QUERY -Scope READ_ONLY -RepoRoot $repoRoot -StateRoot $stateRoot `
        -MachineAlias test-laptop -CallerKind $caller
    Assert-AuthorityTest (-not $expiredResult.Ready) 'expired receipt was accepted'
    $operationMatrix=@(
        @('TDX','ROUTE_QUERY','READ_ONLY'),@('GOOGLE','ROUTE_QUERY','READ_ONLY'),@('LINE','CONNECTIVITY_PROBE','READ_ONLY'),
        @('BOOKING','AVAILABILITY_QUERY','READ_ONLY'),@('BOOKING','INVENTORY_MUTATION','MUTATION'),
        @('BOOKING','BOOKING_CREATE','MUTATION'),@('PAYMENT','PAYMENT','MUTATION'),
        @('BOOKING','CANCELLATION','MUTATION'),@('BOOKING','REFUND','MUTATION')
    )
    $nonces=[Collections.Generic.HashSet[string]]::new()
    foreach($entry in $operationMatrix){
        $matrixReceipt=New-EnvironmentExternalAuthorityReceipt -Provider $entry[0] -OperationClass $entry[1] -Scope $entry[2] `
            -RepoRoot $repoRoot -StateRoot $stateRoot -MachineAlias test-laptop -CallerKind $caller
        Assert-AuthorityTest ($nonces.Add($matrixReceipt.ReceiptId)) 'authority receipt nonce was reused'
        $matrixResult=Test-EnvironmentExternalAuthorityReceipt -ReceiptPath $matrixReceipt.Path -Provider $entry[0] `
            -OperationClass $entry[1] -Scope $entry[2] -RepoRoot $repoRoot -StateRoot $stateRoot `
            -MachineAlias test-laptop -CallerKind $caller
        Assert-AuthorityTest $matrixResult.Ready "approved provider operation matrix entry was rejected: $($entry -join '/')"
    }
    [pscustomobject]@{status='passed';assertions=$assertions}|ConvertTo-Json -Compress
} finally { if(Test-Path -LiteralPath $stateRoot){Remove-Item -LiteralPath $stateRoot -Recurse -Force} }
