[CmdletBinding()]
param(
    [ValidateSet('Summary', 'Classes', 'VerifyReports')]
    [string]$Mode = 'Summary',
    [ValidateSet('all', 'fast', 'integration', 'live')]
    [string]$Lane = 'all',
    [ValidateRange(1, 16)]
    [int]$ShardCount = 1,
    [ValidateRange(0, 15)]
    [int]$ShardIndex = 0,
    [string[]]$Modules = @(),
    [string[]]$ReportRoots = @(),
    [switch]$AsMavenList,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $repoRoot 'src\test\java'
$packageRoot = Join-Path $testRoot 'com\aproject\aidriven\mymobilesecretary'

if ($ShardIndex -ge $ShardCount) {
    throw "ShardIndex $ShardIndex must be smaller than ShardCount $ShardCount."
}

function Get-StableShard {
    param([Parameter(Mandatory)][string]$Value, [Parameter(Mandatory)][int]$Count)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        $number = ([uint32]$hash[0] -shl 24) -bor
                ([uint32]$hash[1] -shl 16) -bor
                ([uint32]$hash[2] -shl 8) -bor
                [uint32]$hash[3]
        return [int]($number % [uint32]$Count)
    } finally {
        $algorithm.Dispose()
    }
}

$classes = [Collections.Generic.List[object]]::new()
Get-ChildItem -LiteralPath $testRoot -Filter '*.java' -File -Recurse |
    Sort-Object FullName |
    ForEach-Object {
        $source = [IO.File]::ReadAllText($_.FullName, [Text.Encoding]::UTF8)
        $classMatch = [regex]::Match(
                $source,
                '(?m)^\s*(?:public\s+)?(?:abstract\s+)?class\s+([A-Za-z0-9_]+)(?:\s+extends\s+([A-Za-z0-9_$.]+))?')
        if (-not $classMatch.Success) { return }
        $packageMatch = [regex]::Match($source, '(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*;')
        if (-not $packageMatch.Success) { return }

        $name = $classMatch.Groups[1].Value
        $parent = $classMatch.Groups[2].Value
        if ($parent.Contains('.')) { $parent = $parent.Substring($parent.LastIndexOf('.') + 1) }
        $relativePath = $_.FullName.Substring($packageRoot.Length).TrimStart([char[]]@('\', '/')).Replace('\', '/')
        $hasTests = [regex]::IsMatch(
                $source,
                '(?m)^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)(?:\s|\()')
        $isLive = $source.Contains('@Tag("live")') -or $name.EndsWith('LiveEvaluationTest')
        $integrationSeed = $source.Contains('@Tag("integration")') -or
                $source.Contains('@SpringBootTest') -or
                $source.Contains('@Testcontainers') -or
                $parent -eq 'IntegrationTestBase'
        $traits = [Collections.Generic.List[string]]::new()
        if ($source.Contains('@Tag("migration")') -or $name.Contains('Migration')) { $traits.Add('migration') }
        if ($name.Contains('Rls') -or $source.Contains('@Tag("rls")')) { $traits.Add('rls') }
        if ($relativePath.StartsWith('conversation/') -or $source.Contains('@Tag("conversation')) { $traits.Add('conversation') }
        if ($name.Contains('Latency') -or $source.Contains('latency')) { $traits.Add('latency') }

        $classes.Add([pscustomobject]@{
            Name = $name
            Parent = $parent
            FullyQualifiedName = "$($packageMatch.Groups[1].Value).$name"
            RelativePath = $relativePath
            Module = $relativePath.Split('/')[0]
            HasTests = $hasTests
            IsLive = $isLive
            IsIntegration = $integrationSeed
            Traits = @($traits)
        })
    }

$integrationNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($class in $classes) {
    if ($class.IsIntegration) { [void]$integrationNames.Add($class.Name) }
}
do {
    $changed = $false
    foreach ($class in $classes) {
        if (-not $class.IsIntegration -and $class.Parent -and $integrationNames.Contains($class.Parent)) {
            $class.IsIntegration = $true
            [void]$integrationNames.Add($class.Name)
            $changed = $true
        }
    }
} while ($changed)

$tests = @($classes | Where-Object HasTests | ForEach-Object {
    $primaryLane = if ($_.IsLive) { 'live' } elseif ($_.IsIntegration) { 'integration' } else { 'fast' }
    [pscustomobject]@{
        Name = $_.Name
        FullyQualifiedName = $_.FullyQualifiedName
        RelativePath = $_.RelativePath
        Module = $_.Module
        Lane = $primaryLane
        Traits = $_.Traits
        Shard = Get-StableShard -Value $_.FullyQualifiedName -Count $ShardCount
    }
})

$selected = @($tests | Where-Object {
    ($Lane -eq 'all' -or $_.Lane -eq $Lane) -and
    ($Modules.Count -eq 0 -or $Modules -contains $_.Module) -and
    ($ShardCount -eq 1 -or $_.Shard -eq $ShardIndex)
} | Sort-Object FullyQualifiedName)

if ($Mode -eq 'Summary') {
    $summary = [ordered]@{
        total = $tests.Count
        automated = @($tests | Where-Object Lane -ne 'live').Count
        fast = @($tests | Where-Object Lane -eq 'fast').Count
        integration = @($tests | Where-Object Lane -eq 'integration').Count
        live = @($tests | Where-Object Lane -eq 'live').Count
        migration = @($tests | Where-Object { $_.Traits -contains 'migration' }).Count
        rls = @($tests | Where-Object { $_.Traits -contains 'rls' }).Count
        shardCount = $ShardCount
        shards = @((0..($ShardCount - 1)) | ForEach-Object {
            $index = $_
            [ordered]@{
                index = $index
                integration = @($tests | Where-Object { $_.Lane -eq 'integration' -and $_.Shard -eq $index }).Count
            }
        })
    }
    if ($Json) { $summary | ConvertTo-Json -Depth 5 -Compress } else { [pscustomobject]$summary | Format-List }
    exit 0
}

if ($Mode -eq 'Classes') {
    if ($AsMavenList) {
        ($selected | Select-Object -ExpandProperty FullyQualifiedName) -join ','
    } elseif ($Json) {
        $selected | ConvertTo-Json -Depth 5
    } else {
        $selected
    }
    exit 0
}

if ($ReportRoots.Count -eq 0) { throw 'VerifyReports mode requires at least one ReportRoots value.' }
$actual = [Collections.Generic.Dictionary[string, int]]::new([StringComparer]::Ordinal)
foreach ($reportRoot in $ReportRoots) {
    if (-not (Test-Path -LiteralPath $reportRoot)) { throw "Surefire report root not found: $reportRoot" }
    Get-ChildItem -LiteralPath $reportRoot -Filter 'TEST-*.xml' -File -Recurse | ForEach-Object {
        [xml]$document = Get-Content -LiteralPath $_.FullName -Raw
        $suiteName = [string]$document.testsuite.name
        if ([string]::IsNullOrWhiteSpace($suiteName)) { throw "Surefire report has no suite name: $($_.FullName)" }
        if (-not $actual.ContainsKey($suiteName)) { $actual[$suiteName] = 0 }
        $actual[$suiteName]++
    }
}

$expected = @($tests | Where-Object Lane -ne 'live' | Select-Object -ExpandProperty FullyQualifiedName)
$missing = @($expected | Where-Object { -not $actual.ContainsKey($_) } | Sort-Object)
$unexpected = @($actual.Keys | Where-Object { $_ -notin $expected } | Sort-Object)
$duplicates = @($actual.GetEnumerator() | Where-Object Value -ne 1 | Sort-Object Key |
    ForEach-Object { "$($_.Key)=$($_.Value)" })
$verification = [ordered]@{
    status = if ($missing.Count -eq 0 -and $unexpected.Count -eq 0 -and $duplicates.Count -eq 0) { 'passed' } else { 'failed' }
    expected = $expected.Count
    actual = $actual.Count
    missing = $missing
    unexpected = $unexpected
    duplicates = $duplicates
}
if ($Json) { $verification | ConvertTo-Json -Depth 5 -Compress } else { [pscustomobject]$verification | Format-List }
if ($verification.status -ne 'passed') { exit 1 }
