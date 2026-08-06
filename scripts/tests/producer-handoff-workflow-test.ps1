[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$requestWorkflow = Get-Content -LiteralPath (Join-Path $repoRoot '.github\workflows\producer-handoff-request.yml') -Raw -Encoding utf8
$stateWorkflow = Get-Content -LiteralPath (Join-Path $repoRoot '.github\workflows\producer-handoff-state.yml') -Raw -Encoding utf8
$testGates = Get-Content -LiteralPath (Join-Path $repoRoot '.github\workflows\test-gates.yml') -Raw -Encoding utf8
$producerPolicyText = Get-Content -LiteralPath (Join-Path $repoRoot '.github\producer-handoff-policy.json') -Raw -Encoding utf8
$policy = Get-Content -LiteralPath (Join-Path $repoRoot '.github\merge-policy.json') -Raw -Encoding utf8 | ConvertFrom-Json
$assertions = 0

function Assert-Workflow {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

$combined = $requestWorkflow + [Environment]::NewLine + $stateWorkflow
Assert-Workflow ($combined -notmatch 'pull_request_target') 'producer workflow used pull_request_target'
Assert-Workflow ($combined -notmatch 'uses:\s*[^\r\n]+@v[0-9]') 'producer workflow contains a movable action tag'
Assert-Workflow ($combined -match 'actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1') 'GitHub App token action is not pinned'
Assert-Workflow ($combined -match 'actions/checkout@11d5960a326750d5838078e36cf38b85af677262') 'checkout action is not pinned'
Assert-Workflow ($combined -match 'actions/github-script@f28e40c7f34bde8b3046d885e986cb6290c5673b') 'github-script action is not pinned'
Assert-Workflow ($requestWorkflow -match 'TOOLING_PRODUCER_ENABLED' -and $requestWorkflow -match 'dry_run') 'request workflow lacks kill switch or dry-run'
Assert-Workflow ($requestWorkflow -match 'Verify manual product PR context' -and
    $requestWorkflow -match 'github\.rest\.pulls\.get' -and
    $requestWorkflow -match 'merge_commit_sha !== process\.env\.REQUESTED_SHA') 'manual dispatch does not verify immutable GitHub PR context'
Assert-Workflow ($requestWorkflow -match 'handoffs/requests/\*\.json') 'request workflow is not path-scoped'
Assert-Workflow ($stateWorkflow -match 'handoffs/receipts/\*\.json') 'state workflow is not receipt-scoped'
Assert-Workflow ($stateWorkflow -match "startsWith\(github\.head_ref, 'automation/producer-handoff/'\)") 'state workflow lacks automation branch guard'
Assert-Workflow ($stateWorkflow -match 'gh pr merge .*--auto --merge --match-head-commit') 'state workflow does not defer to protected auto-merge'
Assert-Workflow (([regex]::Matches($stateWorkflow, 'trusted-producer-handoff\.ps1')).Count -ge 2 -and
    $stateWorkflow -match "git show 'origin/main:scripts/producer-handoff.ps1'" -and
    $stateWorkflow -match 'BASE_SHA\):scripts/producer-handoff\.ps1') 'state validation and credentialed rebuild do not load trusted engines'
Assert-Workflow ($stateWorkflow -notmatch 'git push origin HEAD:\$\{\{' -and
    $stateWorkflow -match 'git push origin "HEAD:\$env:HEAD_REF"') 'credentialed rebuild interpolates an untrusted branch expression into PowerShell'
Assert-Workflow ($stateWorkflow -match "vars\.TOOLING_PRODUCER_ENABLED == 'true' &&[\s\S]+github\.event_name == 'workflow_dispatch'") 'durable finalizer bypasses the kill switch'
Assert-Workflow ($producerPolicyText -match '"maxAutomaticRebuilds": 1') 'bounded rebuild contract is missing'
Assert-Workflow ($testGates -match 'types:\s*\[opened, synchronize, reopened, ready_for_review\]') 'Test gates does not run when a draft PR becomes ready for review'
Assert-Workflow ($testGates -match "startsWith\(github\.head_ref, 'automation/producer-handoff/'\)" -and
    $testGates -match 'ValidateStatePr') 'required Merge policy path does not validate producer state PRs'
Assert-Workflow ($testGates -match 'Validate producer request manifest' -and
    $testGates -match '& \$trustedEngine -Mode ValidateRequest' -and
    $testGates -match 'must add exactly one request manifest') 'required Merge policy path does not validate new producer requests before merge'
Assert-Workflow ($testGates -match 'BASE_SHA\):scripts/producer-handoff\.ps1' -and
    $testGates -match '& \$trustedEngine -Mode ValidateStatePr') 'required Merge policy path executes the PR-head producer engine'
Assert-Workflow ($producerPolicyText -match '"evidencePlanPath": "docs/exec-plans/active/calendar-w11-h-two-machine-development-test-plan\.md"' -and
    $producerPolicyText -match '"evidenceGate": "W11-H coordination publication"') 'producer evidence identity is not policy-owned'
$automationPolicies = @($policy.branchPolicies | Where-Object name -eq 'producer-handoff-state')
Assert-Workflow ($automationPolicies.Count -eq 1 -and $automationPolicies[0].exactChangedFiles -eq 2) 'automation branch policy is missing or not state-only'
Assert-Workflow ($automationPolicies[0].forbiddenPaths -contains 'src/**' -and
    $automationPolicies[0].forbiddenPaths -contains 'scripts/**') 'automation branch policy does not forbid source/tooling changes'

[pscustomobject]@{ status = 'passed'; assertions = $assertions; yamlParser = 'contract-only' } | ConvertTo-Json -Compress
