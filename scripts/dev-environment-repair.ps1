[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$PersistUserJavaHome,
    [string]$JavaHome,
    [string]$StateRoot,
    [string]$MachineAlias
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$probe = Get-EnvironmentJavaProbe -RepoRoot $repoRoot
$candidate = if ($JavaHome) { [IO.Path]::GetFullPath($JavaHome) } else { $probe.CandidateHome }
if (-not $candidate) { throw 'No compatible Java 21 candidate was found.' }
$candidateProbe = Invoke-JavaVersionProbe -JavaPath (Join-Path $candidate 'bin\java.exe')
if (-not $candidateProbe.Ready -or $candidateProbe.Major -ne 21) {
    throw 'The selected Java home is not a valid Java 21 JDK.'
}
$oldProcess = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $candidate
    $maven = Invoke-EnvironmentProcess -FilePath $env:ComSpec `
        -RawArguments ('/d /s /c ""{0}" -v"' -f (Join-Path $repoRoot 'mvnw.cmd')) `
        -WorkingDirectory $repoRoot -TimeoutMilliseconds 20000
    if ($maven.ExitCode -ne 0) { throw 'Maven Wrapper did not accept the selected Java home.' }
    if ($PersistUserJavaHome -and $PSCmdlet.ShouldProcess('current Windows user JAVA_HOME', "set to $candidate")) {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $candidate, 'User')
        if ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') -ne $candidate) {
            throw 'User JAVA_HOME verification failed.'
        }
    }
    $contextArguments = @{ RepoRoot=$repoRoot }
    if ($StateRoot) { $contextArguments.StateRoot=$StateRoot }
    if ($MachineAlias) { $contextArguments.MachineAlias=$MachineAlias }
    $context = Get-EnvironmentStateContext @contextArguments
    $receipt = [ordered]@{
        schemaVersion=$script:EnvironmentSchemaVersion;operationId="java-repair-$([guid]::NewGuid())";outcome='READY'
        persisted=[bool]$PersistUserJavaHome;javaMajor=21;verifiedBy='java-version+mvnw-version'
        at=[datetime]::UtcNow.ToString('o')
    }
    Write-CoordinationJsonAtomic -Path (Join-Path $context.ReceiptsPath "$($receipt.operationId).json") -Document $receipt
    [pscustomobject]$receipt | ConvertTo-Json
} finally {
    $env:JAVA_HOME = $oldProcess
}
