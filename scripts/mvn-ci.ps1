[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$ValidateOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArguments
)

$ErrorActionPreference = 'Stop'

if ($env:CI -ne 'true' -or $env:GITHUB_ACTIONS -ne 'true') {
    throw 'mvn-ci.ps1 is restricted to GitHub Actions ephemeral runners; use mvn-safe.ps1 locally.'
}
if (-not $MavenArguments -or $MavenArguments.Count -eq 0) {
    throw 'Provide a Maven goal such as test or test-compile.'
}

$forbidden = @($MavenArguments | Where-Object {
    $_ -in @('clean', 'install', 'deploy', 'spring-boot:run', 'spotless:apply')
})
if ($forbidden.Count -gt 0) {
    throw "The CI Maven entrypoint rejects persistent or source-writing goals: $($forbidden -join ', ')"
}
$goals = @($MavenArguments | Where-Object { -not $_.StartsWith('-') })
if (@($goals | Where-Object { $_ -notin @('test', 'test-compile') }).Count -gt 0) {
    throw "The CI Maven entrypoint allows only test and test-compile: $($goals -join ', ')"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$isWindowsPlatform = [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [Runtime.InteropServices.OSPlatform]::Windows)
$wrapper = if ($isWindowsPlatform) { Join-Path $repoRoot 'mvnw.cmd' } else { Join-Path $repoRoot 'mvnw' }
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw "Maven Wrapper not found: $wrapper" }

if ($ValidateOnly) {
    [pscustomobject]@{
        status = 'passed'
        entrypoint = 'github-actions-ephemeral'
        wrapper = $wrapper
        arguments = $MavenArguments
    } | ConvertTo-Json -Depth 4 -Compress
    exit 0
}

Push-Location $repoRoot
try {
    if ($isWindowsPlatform) {
        & $wrapper -B -ntp '-Dstyle.color=never' @MavenArguments
    } else {
        & bash $wrapper -B -ntp '-Dstyle.color=never' @MavenArguments
    }
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
