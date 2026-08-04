[CmdletBinding(DefaultParameterSetName='Publish')]
param(
    [Parameter(ParameterSetName='Bootstrap',Mandatory)][switch]$BootstrapIssue,
    [Parameter(ParameterSetName='Publish',Mandatory)][switch]$Publish,
    [string]$StateRoot,
    [string]$MachineAlias
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\environment-common.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$contextArguments = @{RepoRoot=$repoRoot}
if ($StateRoot) { $contextArguments.StateRoot=$StateRoot }
if ($MachineAlias) { $contextArguments.MachineAlias=$MachineAlias }
$context = Get-EnvironmentStateContext @contextArguments
if ((Get-EnvironmentCallerContext).IsSandbox) {
    Write-Host 'GitHub environment publishing is host-only; sandbox snapshots remain local.'
    exit 40
}

function Get-GitHubRepositorySlug {
    $git = Get-Command git -ErrorAction Stop
    $remote = (Invoke-EnvironmentProcess -FilePath $git.Source -Arguments @('-C',$repoRoot,'remote','get-url','origin') -TimeoutMilliseconds 3000).Output.Trim()
    if ($remote -match 'github\.com[/:](?<slug>[^/\s]+/[^/\s]+?)(?:\.git)?$') { return $Matches.slug }
    throw 'origin is not a recognizable GitHub repository URL'
}

function Invoke-GitHubJsonApi {
    param([Parameter(Mandatory)][string]$Method,[Parameter(Mandatory)][string]$Endpoint,[Parameter(Mandatory)]$Body)
    [IO.Directory]::CreateDirectory($context.MachineRoot)|Out-Null
    $temp = Join-Path $context.MachineRoot ".github-$([guid]::NewGuid()).json"
    try {
        [IO.File]::WriteAllText($temp,($Body|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
        $gh = Get-Command gh -ErrorAction Stop
        $result = Invoke-EnvironmentProcess -FilePath $gh.Source -Arguments @('api','-X',$Method,$Endpoint,'--input',$temp) -TimeoutMilliseconds 20000
        if ($result.ExitCode -ne 0) { throw "GitHub API $Method $Endpoint failed" }
        if ($result.Output) { return $result.Output | ConvertFrom-Json }
        return $null
    } finally { if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Force} }
}

function Get-GitHubOpenIssues {
    $gh = Get-Command gh -ErrorAction Stop
    $result = Invoke-EnvironmentProcess -FilePath $gh.Source `
        -Arguments @('api',"repos/$slug/issues?state=open&per_page=100") -TimeoutMilliseconds 20000
    if ($result.ExitCode -ne 0) { throw 'GitHub open issues could not be inspected' }
    if (-not $result.Output) { return @() }
    return @($result.Output | ConvertFrom-Json)
}

$ghProbe = Get-EnvironmentGitHubProbe
if (-not $ghProbe.Ready) { Write-Host $ghProbe.Reason; exit 40 }
$slug = Get-GitHubRepositorySlug

if ($BootstrapIssue) {
    $existing = Read-EnvironmentJson -Path $context.GitHubConfigPath
    if ($existing -and $existing.issueNumber) { Write-Host ("GitHub environment issue already configured: #{0}" -f $existing.issueNumber); exit 0 }
    $busMarker = '<!-- mms-environment-status-bus:v1 -->'
    $issue = Get-GitHubOpenIssues | Where-Object {
        $_.PSObject.Properties['body'] -and [string]$_.body -like "*$busMarker*" -and
        -not $_.PSObject.Properties['pull_request']
    } | Sort-Object number | Select-Object -First 1
    if (-not $issue) {
        $issue = Invoke-GitHubJsonApi -Method POST -Endpoint "repos/$slug/issues" -Body ([ordered]@{
            title='Development environment status bus'
            body="$busMarker`nMachine-edited, sanitized development capability summaries. Git handoff and user authorization remain authoritative."
            labels=@()
        })
    }
    Write-CoordinationJsonAtomic -Path $context.GitHubConfigPath -Document ([ordered]@{schemaVersion=1;repository=$slug;issueNumber=[int]$issue.number;commentId=$null})
    Write-Host ("GitHub environment status bus ready: issue #{0}" -f $issue.number)
    exit 0
}

$config = Read-EnvironmentJson -Path $context.GitHubConfigPath
if (-not $config -or -not $config.issueNumber) { Write-Host 'GitHub status bus is not configured; run -BootstrapIssue once.'; exit 40 }
$snapshot = Read-EnvironmentJson -Path $context.SnapshotPath
if (-not $snapshot) { Write-Host 'No local environment snapshot is available.'; exit 40 }
$published = ConvertTo-PublishedEnvironmentSnapshot -Snapshot $snapshot
$comparison = [ordered]@{
    requestedCapability=$published.requestedCapability;callerKind=$published.callerKind
    capability=$published.capability;contractFingerprint=$published.contractFingerprint;tools=$published.tools
}
$publishFingerprint=(Get-CoordinationHash ($comparison|ConvertTo-Json -Depth 10 -Compress)).Substring(0,24)
$lastFingerprint = if ($config.PSObject.Properties['lastFingerprint']) { [string]$config.lastFingerprint } else { $null }
$lastPublishedAt = if ($config.PSObject.Properties['lastPublishedAt']) { [string]$config.lastPublishedAt } else { $null }
if ($lastFingerprint -eq $publishFingerprint -and $lastPublishedAt -and
        [datetime]$lastPublishedAt -gt [datetime]::UtcNow.AddMinutes(-30)) {
    Write-Host ("GitHub environment status unchanged for {0}; publish skipped." -f $context.MachineAlias)
    exit 0
}
$marker = "<!-- mms-environment-status:v1:$($context.MachineAlias) -->"
$body = "$marker`n``````json`n$($published|ConvertTo-Json -Depth 10)`n``````"
$comment = $null
if ($config.commentId) {
    $comment = Invoke-GitHubJsonApi -Method PATCH -Endpoint "repos/$slug/issues/comments/$($config.commentId)" -Body @{body=$body}
} else {
    $commentsResult = Invoke-EnvironmentProcess -FilePath (Get-Command gh -ErrorAction Stop).Source `
        -Arguments @('api',"repos/$slug/issues/$($config.issueNumber)/comments",'--paginate') -TimeoutMilliseconds 20000
    if ($commentsResult.ExitCode -ne 0) { throw 'GitHub issue comments could not be inspected' }
    $comments = if ([string]::IsNullOrWhiteSpace($commentsResult.Output)) {
        @()
    } else {
        @($commentsResult.Output | ConvertFrom-Json)
    }
    $existingComment = $comments | Where-Object {
        $_ -and $_.PSObject.Properties['body'] -and [string]$_.body -like "$marker*"
    } | Select-Object -First 1
    if ($existingComment) { $comment=Invoke-GitHubJsonApi -Method PATCH -Endpoint "repos/$slug/issues/comments/$($existingComment.id)" -Body @{body=$body} }
    else { $comment=Invoke-GitHubJsonApi -Method POST -Endpoint "repos/$slug/issues/$($config.issueNumber)/comments" -Body @{body=$body} }
}
Write-CoordinationJsonAtomic -Path $context.GitHubConfigPath -Document ([ordered]@{
    schemaVersion=1;repository=$slug;issueNumber=[int]$config.issueNumber;commentId=[long]$comment.id
    lastPublishedAt=[datetime]::UtcNow.ToString('o');lastFingerprint=$publishFingerprint
})
Write-Host ("Published sanitized environment status for {0}." -f $context.MachineAlias)
