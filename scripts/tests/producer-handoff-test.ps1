[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptsRoot
$engine = Join-Path $scriptsRoot 'producer-handoff.ps1'
$policy = Join-Path $repoRoot '.github\producer-handoff-policy.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('mms-producer-handoff-' + [guid]::NewGuid().ToString('n'))
$assertions = 0
$productSha = '1111111111111111111111111111111111111111'
$productPr = 'https://github.com/wei310129/my-mobile-secretary/pull/101'
$workflowRun = 'https://github.com/wei310129/my-mobile-secretary/actions/runs/202'
$requestRelative = 'docs/exec-plans/active/handoffs/requests/route-benchmark-pilot.json'
$stateRelative = 'docs/exec-plans/active/handoffs/laptop-trigger-state.json'
$planRelative = 'docs/exec-plans/active/calendar-w11-h-two-machine-development-test-plan.md'

function Assert-ProducerTest {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function Write-TestJson {
    param([string]$Relative, [object]$Document)
    $path = Join-Path $tempRoot ($Relative.Replace('/', '\'))
    [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [IO.File]::WriteAllText($path, (($Document | ConvertTo-Json -Depth 30) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    return $path
}

function Write-TestText {
    param([string]$Relative, [string]$Text)
    $path = Join-Path $tempRoot ($Relative.Replace('/', '\'))
    [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [IO.File]::WriteAllText($path, $Text, [Text.UTF8Encoding]::new($false))
    return $path
}

function New-Request {
    return [ordered]@{
        schemaVersion = 1
        requestId = 'route-benchmark-pilot'
        eventId = 'TR-DESKTOP-ROUTE-BENCHMARK-START'
        evidence = [ordered]@{ planPath = $planRelative; gate = 'W11-H coordination publication' }
    }
}

function New-State {
    return [ordered]@{
        schemaVersion = 1
        ownerLane = 'laptop'
        updatedOn = '2026-08-01'
        triggers = [ordered]@{
            'TR-UNRELATED' = [ordered]@{ status = 'PENDING'; publishedSha = $null }
            'TR-DESKTOP-ROUTE-BENCHMARK-START' = [ordered]@{
                status = 'BLOCKED'
                publishedSha = $null
                baseSha = '0000000000000000000000000000000000000000'
                reason = 'Coordination plan has not been published.'
                allowedPaths = @(
                    'docs/exec-plans/evidence/calendar-w11-h/desktop-route-benchmark/**',
                    'docs/exec-plans/evidence/calendar-w11-h/sealed-holdout/**',
                    'docs/exec-plans/evidence/calendar-w11-h/public-response-evaluation/**',
                    'docs/exec-plans/evidence/development-environment/calendar-w11-desktop.json'
                )
                forbiddenPaths = @('src/**', 'pom.xml', 'scripts/**', 'src/main/resources/db/migration/**')
                resourceClaims = @()
                externalMutationCountRequired = 0
            }
        }
    }
}

function Expected-Failure {
    param([hashtable]$Arguments, [string]$Pattern)
    try {
        & $engine @Arguments | Out-Null
        return $false
    } catch {
        return $_.Exception.Message -match $Pattern
    }
}

try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    [void](Write-TestText -Relative $planRelative -Text '# W11-H test plan')
    $requestPath = Write-TestJson -Relative $requestRelative -Document (New-Request)
    $statePath = Write-TestJson -Relative $stateRelative -Document (New-State)
    $common = @{ RepositoryRoot = $tempRoot; PolicyPath = $policy; RequestPath = $requestPath; Json = $true }

    $valid = & $engine -Mode ValidateRequest @common | ConvertFrom-Json
    Assert-ProducerTest ($valid.status -eq 'passed' -and $valid.resourceClaims -eq 0) 'valid request was rejected'

    $prepare = & $engine -Mode PrepareState @common -StatePath $statePath -ProductMergeSha $productSha `
        -ProductPrUrl $productPr -WorkflowRunUrl $workflowRun -PublishedAt '2026-08-07T01:02:03Z' | ConvertFrom-Json
    Assert-ProducerTest ($prepare.status -eq 'prepared' -and $prepare.branch -like 'automation/producer-handoff/*') 'valid state was not prepared'
    $preparedState = Get-Content -LiteralPath $statePath -Raw -Encoding utf8 | ConvertFrom-Json
    $preparedTrigger = $preparedState.triggers.'TR-DESKTOP-ROUTE-BENCHMARK-START'
    Assert-ProducerTest ($preparedTrigger.status -eq 'READY' -and $preparedTrigger.publishedSha -eq $productSha -and
        $preparedTrigger.baseSha -eq $productSha -and $null -eq $preparedTrigger.PSObject.Properties['reason']) 'state transition was not minimal and complete'
    $receiptPath = Join-Path $tempRoot ($prepare.receiptPath.Replace('/', '\'))
    $receipt = Get-Content -LiteralPath $receiptPath -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-ProducerTest ($receipt.resourceClaims.Count -eq 0 -and $receipt.externalMutationCount -eq 0 -and $receipt.rebuildCount -eq 0) 'receipt violated zero-mutation policy'

    $second = & $engine -Mode PrepareState @common -StatePath $statePath -ProductMergeSha $productSha `
        -ProductPrUrl $productPr -WorkflowRunUrl $workflowRun -PublishedAt '2026-08-07T01:02:03Z' | ConvertFrom-Json
    Assert-ProducerTest ($second.status -eq 'already-prepared') 'idempotent prepare created a second transition'

    $baseStatePath = Write-TestJson -Relative 'fixtures/base-state.json' -Document (New-State)
    $changedPath = Write-TestJson -Relative 'fixtures/changed.json' -Document @(
        @{ filename = $stateRelative; status = 'modified' },
        @{ filename = $prepare.receiptPath; status = 'added' }
    )
    $validation = & $engine -Mode ValidateStatePr @common -BaseStatePath $baseStatePath -HeadStatePath $statePath `
        -ReceiptPath $receiptPath -ChangedFilesJson $changedPath -BaseSha $productSha -SkipAncestryValidation | ConvertFrom-Json
    Assert-ProducerTest ($validation.status -eq 'passed') 'valid state-only PR was rejected'

    $unrelatedBase = New-State
    $unrelatedBase.triggers.'TR-UNRELATED'.status = 'READY'
    $unrelatedBase.triggers.'TR-UNRELATED'.publishedSha = '2222222222222222222222222222222222222222'
    $unrelatedBasePath = Write-TestJson -Relative 'fixtures/unrelated-base.json' -Document $unrelatedBase
    $rebuildSignal = & $engine -Mode ValidateStatePr @common -BaseStatePath $unrelatedBasePath -HeadStatePath $statePath `
        -ReceiptPath $receiptPath -ChangedFilesJson $changedPath -BaseSha $productSha -SkipAncestryValidation | ConvertFrom-Json
    Assert-ProducerTest ($rebuildSignal.status -eq 'rebuild-required') 'unrelated state advance did not request one automatic rebuild'
    $rebuilt = & $engine -Mode RebuildState @common -BaseStatePath $unrelatedBasePath -HeadStatePath $statePath -ReceiptPath $receiptPath | ConvertFrom-Json
    Assert-ProducerTest ($rebuilt.status -eq 'rebuilt' -and $rebuilt.rebuildCount -eq 1) 'automatic rebuild did not record its bound'
    $rebuiltValidation = & $engine -Mode ValidateStatePr @common -BaseStatePath $unrelatedBasePath -HeadStatePath $statePath `
        -ReceiptPath $receiptPath -ChangedFilesJson $changedPath -BaseSha $productSha -SkipAncestryValidation | ConvertFrom-Json
    Assert-ProducerTest ($rebuiltValidation.status -eq 'passed') 'rebuilt state-only PR did not validate'

    $staleBase = New-State
    $staleBase.triggers.'TR-DESKTOP-ROUTE-BENCHMARK-START'.reason = 'A different producer changed this trigger.'
    $staleBasePath = Write-TestJson -Relative 'fixtures/stale-base.json' -Document $staleBase
    $staleArguments = $common.Clone()
    $staleArguments.Mode = 'ValidateStatePr'
    $staleArguments.BaseStatePath = $staleBasePath
    $staleArguments.HeadStatePath = $statePath
    $staleArguments.ReceiptPath = $receiptPath
    $staleArguments.ChangedFilesJson = $changedPath
    $staleArguments.BaseSha = $productSha
    $staleArguments.SkipAncestryValidation = $true
    Assert-ProducerTest (Expected-Failure -Arguments $staleArguments -Pattern 'mark the request STALE') 'target trigger drift was accepted'

    $badRequest = New-Request
    $badRequest.targetStatus = 'READY'
    $badRequestPath = Write-TestJson -Relative 'docs/exec-plans/active/handoffs/requests/bad-request.json' -Document $badRequest
    $badArguments = $common.Clone()
    $badArguments.Mode = 'ValidateRequest'
    $badArguments.RequestPath = $badRequestPath
    Assert-ProducerTest (Expected-Failure -Arguments $badArguments -Pattern 'unknown fields') 'manifest overrode a policy-owned field'

    $wrongEvidence = New-Request
    $wrongEvidence.requestId = 'wrong-evidence'
    $wrongEvidence.evidence.planPath = 'docs/exec-plans/active/unrelated-plan.md'
    [void](Write-TestText -Relative $wrongEvidence.evidence.planPath -Text '# Unrelated plan')
    $wrongEvidencePath = Write-TestJson -Relative 'docs/exec-plans/active/handoffs/requests/wrong-evidence.json' -Document $wrongEvidence
    $wrongEvidenceArguments = $common.Clone()
    $wrongEvidenceArguments.Mode = 'ValidateRequest'
    $wrongEvidenceArguments.RequestPath = $wrongEvidencePath
    Assert-ProducerTest (Expected-Failure -Arguments $wrongEvidenceArguments -Pattern 'policy-owned plan and gate') 'request selected a non-policy evidence plan'

    $badEvent = New-Request
    $badEvent.requestId = 'unknown-event'
    $badEvent.eventId = 'TR-SCHEMA-B3-DURABLE-GRANT'
    $badEventPath = Write-TestJson -Relative 'docs/exec-plans/active/handoffs/requests/unknown-event.json' -Document $badEvent
    $badEventArguments = $common.Clone()
    $badEventArguments.Mode = 'ValidateRequest'
    $badEventArguments.RequestPath = $badEventPath
    Assert-ProducerTest (Expected-Failure -Arguments $badEventArguments -Pattern 'not uniquely allowlisted') 'high-risk unknown event was accepted'

    $extraChangedPath = Write-TestJson -Relative 'fixtures/extra-changed.json' -Document @(
        @{ filename = $stateRelative; status = 'modified' },
        @{ filename = $prepare.receiptPath; status = 'added' },
        @{ filename = 'src/main/java/Unsafe.java'; status = 'added' }
    )
    $extraArguments = $common.Clone()
    $extraArguments.Mode = 'ValidateStatePr'
    $extraArguments.BaseStatePath = $unrelatedBasePath
    $extraArguments.HeadStatePath = $statePath
    $extraArguments.ReceiptPath = $receiptPath
    $extraArguments.ChangedFilesJson = $extraChangedPath
    $extraArguments.BaseSha = $productSha
    $extraArguments.SkipAncestryValidation = $true
    Assert-ProducerTest (Expected-Failure -Arguments $extraArguments -Pattern 'exactly two') 'state-only PR accepted a source file'

    $claimedState = New-State
    $claimedState.triggers.'TR-DESKTOP-ROUTE-BENCHMARK-START'.resourceClaims = @('source')
    $claimedStatePath = Write-TestJson -Relative 'fixtures/claimed-state.json' -Document $claimedState
    $claimedArguments = $common.Clone()
    $claimedArguments.Mode = 'ValidateRequest'
    $claimedArguments.StatePath = $claimedStatePath
    Assert-ProducerTest (Expected-Failure -Arguments $claimedArguments -Pattern 'zero resource claims') 'live trigger with a resource claim was accepted'

    $wrongAllowlistState = New-State
    $wrongAllowlistState.triggers.'TR-DESKTOP-ROUTE-BENCHMARK-START'.allowedPaths = @('docs/**')
    $wrongAllowlistPath = Write-TestJson -Relative 'fixtures/wrong-allowlist-state.json' -Document $wrongAllowlistState
    $wrongAllowlistArguments = $common.Clone()
    $wrongAllowlistArguments.Mode = 'ValidateRequest'
    $wrongAllowlistArguments.StatePath = $wrongAllowlistPath
    Assert-ProducerTest (Expected-Failure -Arguments $wrongAllowlistArguments -Pattern 'allowlist or forbidden') 'live trigger overbroad allowlist was accepted'

    $wrongReceiptPath = Join-Path $tempRoot 'docs\exec-plans\active\handoffs\receipts\wrong-name.json'
    Copy-Item -LiteralPath $receiptPath -Destination $wrongReceiptPath
    $wrongFinalizeArguments = $common.Clone()
    $wrongFinalizeArguments.Mode = 'Finalize'
    $wrongFinalizeArguments.StatePath = $statePath
    $wrongFinalizeArguments.ReceiptPath = $wrongReceiptPath
    Assert-ProducerTest (Expected-Failure -Arguments $wrongFinalizeArguments -Pattern 'deterministic identity') 'finalizer accepted a non-deterministic receipt path'

    $final = & $engine -Mode Finalize @common -StatePath $statePath -ReceiptPath $receiptPath | ConvertFrom-Json
    Assert-ProducerTest ($final.status -eq 'ready-to-notify' -and $final.marker -like '<!-- mms-producer-handoff:*') 'merged receipt was not ready for idempotent notification'

    [pscustomobject]@{ status = 'passed'; assertions = $assertions; liveGitHub = 'skipped'; externalMutations = 0 } | ConvertTo-Json -Compress
} finally {
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($safeTemp, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolved).StartsWith('mms-producer-handoff-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
