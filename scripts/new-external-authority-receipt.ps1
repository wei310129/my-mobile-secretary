[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('TDX','GOOGLE','LINE','BOOKING','PAYMENT')][string]$Provider,
    [Parameter(Mandatory)][ValidateSet('ROUTE_QUERY','CONNECTIVITY_PROBE','AVAILABILITY_QUERY','INVENTORY_MUTATION','BOOKING_CREATE','PAYMENT','CANCELLATION','REFUND')][string]$OperationClass,
    [Parameter(Mandatory)][ValidateSet('READ_ONLY','MUTATION')][string]$Scope,
    [Parameter(Mandatory)][switch]$UserAuthorizedThisTurn,
    [ValidateRange(1,15)][int]$TtlMinutes=5,[string]$StateRoot,[string]$MachineAlias,[switch]$Json
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\environment-common.ps1"
if(-not $UserAuthorizedThisTurn){throw 'explicit user authority for this provider operation is required in the current turn'}
$arguments=@{Provider=$Provider;OperationClass=$OperationClass;Scope=$Scope;RepoRoot=(Split-Path -Parent $PSScriptRoot);TtlMinutes=$TtlMinutes}
if($StateRoot){$arguments.StateRoot=$StateRoot};if($MachineAlias){$arguments.MachineAlias=$MachineAlias}
$issued=New-EnvironmentExternalAuthorityReceipt @arguments
if($Json){[pscustomobject]@{receiptId=$issued.ReceiptId;receiptPath=$issued.Path;expiresAt=$issued.Receipt.expiresAt;provider=$Provider;operationClass=$OperationClass;scope=$Scope}|ConvertTo-Json -Compress}
else{Write-Host ("External authority receipt issued: id={0}; expiresAt={1}; provider={2}; operation={3}; scope={4}" -f $issued.ReceiptId,$issued.Receipt.expiresAt,$Provider,$OperationClass,$Scope)}
