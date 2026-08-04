[CmdletBinding()]
param(
    [ValidateSet('Fast', 'Relevant', 'Integration', 'Full')]
    [string]$Lane = 'Relevant',
    [string[]]$ChangedPaths = @(),
    [ValidateRange(1, 16)]
    [int]$ShardCount = 1,
    [ValidateRange(0, 15)]
    [int]$ShardIndex = 0,
    [switch]$Ci,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$inventory = Join-Path $PSScriptRoot 'test-inventory.ps1'
$localMaven = Join-Path $PSScriptRoot 'mvn-safe.ps1'
$ciMaven = Join-Path $PSScriptRoot 'mvn-ci.ps1'

function Invoke-TestMaven {
    param([Parameter(Mandatory)][string[]]$Arguments)

    if ($DryRun) {
        Write-Output ("MAVEN {0}" -f ($Arguments -join ' '))
        return
    }
    if ($Ci) { & $ciMaven @Arguments } else { & $localMaven @Arguments }
    if ($LASTEXITCODE -ne 0) { throw "Maven test failed, exit code=$LASTEXITCODE" }
}

function Get-IntegrationClassList {
    param([string[]]$SelectedModules = @())

    if ($SelectedModules.Count -gt 0) {
        return (& $inventory -Mode Classes -Lane integration -ShardCount $ShardCount `
                -ShardIndex $ShardIndex -Modules $SelectedModules -AsMavenList | Out-String).Trim()
    }
    return (& $inventory -Mode Classes -Lane integration -ShardCount $ShardCount `
            -ShardIndex $ShardIndex -AsMavenList | Out-String).Trim()
}

function Invoke-FastLane {
    Write-Output 'TEST_LANE fast'
    Invoke-TestMaven -Arguments @('-Ptest-fast', 'test')
}

function Invoke-IntegrationLane {
    param([string[]]$SelectedModules = @())

    $classList = Get-IntegrationClassList -SelectedModules $SelectedModules
    if ([string]::IsNullOrWhiteSpace($classList)) {
        Write-Output 'TEST_LANE integration skipped=no-matching-classes'
        return
    }
    $classCount = @($classList.Split(',')).Count
    Write-Output "TEST_LANE integration classes=$classCount shard=$ShardIndex/$ShardCount"
    Invoke-TestMaven -Arguments @('-Ptest-automated', "-Dtest=$classList", 'test')
}

if ($Lane -eq 'Fast') { Invoke-FastLane; exit 0 }
if ($Lane -eq 'Integration') { Invoke-IntegrationLane; exit 0 }
if ($Lane -eq 'Full') {
    Write-Output 'TEST_LANE full'
    Invoke-TestMaven -Arguments @('-Ptest-automated', 'test')
    exit 0
}

if ($ChangedPaths.Count -eq 0) {
    $statusLines = @(git -C $repoRoot status --porcelain=v1 -uall)
    foreach ($line in $statusLines) {
        if ($line.Length -lt 4) { continue }
        $path = $line.Substring(3).Trim()
        if ($path.Contains(' -> ')) { $path = $path.Substring($path.IndexOf(' -> ') + 4) }
        $ChangedPaths += $path.Replace('\', '/')
    }
}

$normalizedPaths = @($ChangedPaths | ForEach-Object { $_.Replace('\', '/').Trim() } |
    Where-Object { $_ } | Sort-Object -Unique)
$fullReasons = [Collections.Generic.List[string]]::new()
$modules = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($path in $normalizedPaths) {
    if ($path -eq 'pom.xml' -or
            $path.StartsWith('src/main/resources/db/migration/') -or
            $path -eq 'src/test/java/com/aproject/aidriven/mymobilesecretary/IntegrationTestBase.java' -or
            $path -eq 'src/test/java/com/aproject/aidriven/mymobilesecretary/TestcontainersConfiguration.java' -or
            $path.StartsWith('scripts/test') -or $path -eq 'scripts/mvn-ci.ps1') {
        $fullReasons.Add($path)
        continue
    }
    $match = [regex]::Match(
            $path,
            '^src/(?:main|test)/java/com/aproject/aidriven/mymobilesecretary/([^/]+)/')
    if ($match.Success) {
        $module = $match.Groups[1].Value
        if ($module -in @('api', 'shared')) { $fullReasons.Add($path) } else { [void]$modules.Add($module) }
        continue
    }
    if (-not ($path.StartsWith('docs/') -or $path.StartsWith('.github/'))) {
        $fullReasons.Add($path)
    }
}
if ($modules.Count -ge 3) { $fullReasons.Add("cross-module:$($modules.Count)") }

if ($fullReasons.Count -gt 0) {
    Write-Output ("TEST_ROUTE full reasons={0}" -f (($fullReasons | Sort-Object -Unique) -join ','))
    Invoke-TestMaven -Arguments @('-Ptest-automated', 'test')
    exit 0
}

Write-Output ("TEST_ROUTE relevant paths={0} modules={1}" -f $normalizedPaths.Count, (($modules | Sort-Object) -join ','))
Invoke-FastLane
if ($modules.Count -eq 0) { exit 0 }

$relatedModules = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($module in $modules) {
    [void]$relatedModules.Add($module)
    if ($module -eq 'intent') { [void]$relatedModules.Add('conversation') }
    if ($module -eq 'conversation') { [void]$relatedModules.Add('intent') }
    if ($module -in @('booking', 'execution', 'payment')) {
        [void]$relatedModules.Add('booking'); [void]$relatedModules.Add('execution'); [void]$relatedModules.Add('payment')
    }
}
Invoke-IntegrationLane -SelectedModules @($relatedModules)
