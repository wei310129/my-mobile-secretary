# Shared Maven runner: discard successful console output and emit only bounded diagnostics on failure.

$MavenRepoRoot = Split-Path -Parent $PSScriptRoot
$MavenWrapper = Join-Path $MavenRepoRoot 'mvnw.cmd'

function Add-MavenOutputArguments {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $result = [System.Collections.Generic.List[string]]::new()
    if (-not ($Arguments | Where-Object { $_ -eq '-q' -or $_ -eq '--quiet' })) {
        $result.Add('-q')
    }
    if (-not ($Arguments | Where-Object { $_ -eq '-ntp' -or $_ -eq '--no-transfer-progress' })) {
        $result.Add('-ntp')
    }
    if (-not ($Arguments | Where-Object { $_ -like '-Dstyle.color=*' })) {
        $result.Add('-Dstyle.color=never')
    }
    foreach ($argument in $Arguments) {
        $result.Add($argument)
    }
    return ,$result.ToArray()
}

function ConvertTo-CmdArgument {
    param([AllowEmptyString()][string]$Argument)

    if ($Argument -notmatch '[\s"&|<>^()]') {
        return $Argument
    }
    return '"{0}"' -f ($Argument -replace '(\\*)"', '$1$1\"')
}

function Get-MavenProjectDirectory {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $projectFile = $null
    for ($index = 0; $index -lt $Arguments.Count; $index++) {
        $argument = $Arguments[$index]
        if (($argument -eq '-f' -or $argument -eq '--file') -and $index + 1 -lt $Arguments.Count) {
            $projectFile = $Arguments[$index + 1]
            break
        }
        if ($argument -match '^(?:-f|--file)=(.+)$') {
            $projectFile = $Matches[1]
            break
        }
    }

    if (-not $projectFile) {
        return $MavenRepoRoot
    }

    $candidate = $projectFile
    if (-not [System.IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $MavenRepoRoot $candidate
    }
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        return [System.IO.Path]::GetFullPath($candidate)
    }
    return Split-Path -Parent ([System.IO.Path]::GetFullPath($candidate))
}

function Get-SurefireReportSnapshot {
    param([Parameter(Mandatory)][string]$ReportDirectory)

    $snapshot = @{}
    if (-not (Test-Path -LiteralPath $ReportDirectory)) {
        return $snapshot
    }
    Get-ChildItem -LiteralPath $ReportDirectory -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue |
        ForEach-Object {
            $snapshot[$_.FullName] = '{0}:{1}' -f $_.LastWriteTimeUtc.Ticks, $_.Length
        }
    return $snapshot
}

function Get-UpdatedSurefireReports {
    param(
        [Parameter(Mandatory)][string]$ReportDirectory,
        [Parameter(Mandatory)][hashtable]$Snapshot
    )

    if (-not (Test-Path -LiteralPath $ReportDirectory)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $ReportDirectory -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue |
        Where-Object {
            $current = '{0}:{1}' -f $_.LastWriteTimeUtc.Ticks, $_.Length
            -not $Snapshot.ContainsKey($_.FullName) -or $Snapshot[$_.FullName] -ne $current
        } |
        Sort-Object FullName)
}

function Read-BoundedXmlElementText {
    param(
        [Parameter(Mandatory)][System.Xml.XmlReader]$Reader,
        [Parameter(Mandatory)][int]$CharacterLimit
    )

    if ($Reader.IsEmptyElement) {
        return [pscustomobject]@{ Text = ''; Truncated = $false }
    }

    $startDepth = $Reader.Depth
    $builder = [System.Text.StringBuilder]::new()
    $truncated = $false
    while ($Reader.Read()) {
        if ($Reader.NodeType -eq [System.Xml.XmlNodeType]::EndElement -and
                $Reader.Depth -eq $startDepth) {
            break
        }
        if ($Reader.NodeType -ne [System.Xml.XmlNodeType]::Text -and
                $Reader.NodeType -ne [System.Xml.XmlNodeType]::CDATA) {
            continue
        }

        $remaining = $CharacterLimit - $builder.Length
        if ($remaining -le 0) {
            $truncated = $true
            continue
        }
        $value = $Reader.Value
        if ($value.Length -le $remaining) {
            [void]$builder.Append($value)
        } else {
            [void]$builder.Append($value.Substring(0, $remaining))
            $truncated = $true
        }
    }
    return [pscustomobject]@{ Text = $builder.ToString(); Truncated = $truncated }
}

function ConvertTo-ReportInteger {
    param([AllowNull()][string]$Value)

    $number = 0
    if ($Value) {
        [void][int]::TryParse($Value, [ref]$number)
    }
    return $number
}

function Read-SurefireSummary {
    param(
        [Parameter(Mandatory)][AllowNull()][AllowEmptyCollection()][System.IO.FileInfo[]]$Reports,
        [int]$FailureTextCharacterLimit = 24000
    )

    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    $failureDetails = [System.Collections.Generic.List[object]]::new()
    $parseErrors = [System.Collections.Generic.List[string]]::new()

    foreach ($report in @($Reports)) {
        $settings = [System.Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = $null
        $suiteCounted = $false
        $testClass = ''
        $testName = ''
        try {
            $reader = [System.Xml.XmlReader]::Create($report.FullName, $settings)
            while ($reader.Read()) {
                if ($reader.NodeType -ne [System.Xml.XmlNodeType]::Element) {
                    continue
                }
                if ($reader.LocalName -eq 'testsuite' -and -not $suiteCounted) {
                    $tests += ConvertTo-ReportInteger $reader.GetAttribute('tests')
                    $failures += ConvertTo-ReportInteger $reader.GetAttribute('failures')
                    $errors += ConvertTo-ReportInteger $reader.GetAttribute('errors')
                    $skipped += ConvertTo-ReportInteger $reader.GetAttribute('skipped')
                    $suiteCounted = $true
                    continue
                }
                if ($reader.LocalName -eq 'testcase') {
                    $testClass = $reader.GetAttribute('classname')
                    $testName = $reader.GetAttribute('name')
                    continue
                }
                if ($reader.LocalName -ne 'failure' -and $reader.LocalName -ne 'error') {
                    continue
                }

                $kind = $reader.LocalName
                $type = $reader.GetAttribute('type')
                $message = $reader.GetAttribute('message')
                $failureText = Read-BoundedXmlElementText `
                    -Reader $reader `
                    -CharacterLimit $FailureTextCharacterLimit
                $failureDetails.Add([pscustomobject]@{
                    ClassName = $testClass
                    TestName = $testName
                    Kind = $kind
                    Type = $type
                    Message = $message
                    Text = $failureText.Text
                    TextTruncated = $failureText.Truncated
                })
            }
        } catch {
            $parseErrors.Add(('{0}: {1}' -f $report.Name, $_.Exception.Message))
        } finally {
            if ($reader) {
                $reader.Dispose()
            }
        }
    }

    return [pscustomobject]@{
        Tests = $tests
        Failures = $failures
        Errors = $errors
        Skipped = $skipped
        FailureDetails = $failureDetails.ToArray()
        ParseErrors = $parseErrors.ToArray()
    }
}

function Limit-DiagnosticLines {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory)][int]$LineLimit,
        [Parameter(Mandatory)][int]$CharacterLimit,
        [Parameter(Mandatory)][string]$OmissionMessage,
        [switch]$AlreadyTruncated
    )

    $physicalLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $Lines) {
        if ($null -eq $line) {
            $physicalLines.Add('')
            continue
        }
        foreach ($physicalLine in ([string]$line -split "`r?`n")) {
            $physicalLines.Add($physicalLine)
        }
    }

    $bounded = [System.Collections.Generic.List[string]]::new()
    $characterCount = 0
    $truncated = $AlreadyTruncated.IsPresent
    $contentLineLimit = [Math]::Max(0, $LineLimit - 1)
    $contentCharacterLimit = [Math]::Max(0, $CharacterLimit - $OmissionMessage.Length - 2)

    foreach ($line in $physicalLines) {
        $lineText = if ($null -eq $line) { '' } else { [string]$line }
        if ($bounded.Count -ge $contentLineLimit) {
            $truncated = $true
            break
        }
        $remainingCharacters = $contentCharacterLimit - $characterCount - 1
        if ($remainingCharacters -le 0) {
            $truncated = $true
            break
        }
        if ($lineText.Length -gt $remainingCharacters) {
            $bounded.Add($lineText.Substring(0, $remainingCharacters))
            $characterCount += $remainingCharacters + 1
            $truncated = $true
            break
        }
        $bounded.Add($lineText)
        $characterCount += $lineText.Length + 1
    }

    if (-not $truncated -and $bounded.Count -eq $physicalLines.Count) {
        return ,$bounded.ToArray()
    }
    if ($LineLimit -gt 0 -and $CharacterLimit -gt 0) {
        while ($bounded.Count -ge $LineLimit) {
            $bounded.RemoveAt($bounded.Count - 1)
        }
        $remaining = $CharacterLimit - (($bounded.ToArray() -join "`n").Length) - 1
        if ($remaining -gt 0) {
            $bounded.Add($OmissionMessage.Substring(0, [Math]::Min($remaining, $OmissionMessage.Length)))
        }
    }
    return ,$bounded.ToArray()
}

function Get-FailureDiagnosticLines {
    param(
        [Parameter(Mandatory)]$Failure,
        [int]$LineLimit = 40,
        [int]$CharacterLimit = 12000
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $identity = '{0}#{1}' -f $Failure.ClassName, $Failure.TestName
    $lines.Add(('FAIL {0}' -f $identity.Trim('#')))

    $detail = @($Failure.Type, $Failure.Message) |
        Where-Object { $_ } |
        ForEach-Object { $_.Trim() } |
        Select-Object -Unique
    if ($detail.Count -gt 0) {
        $detailText = '{0}: {1}' -f $Failure.Kind, ($detail -join ': ')
        foreach ($detailLine in ($detailText -split "`r?`n")) {
            $lines.Add(('  {0}' -f $detailLine.TrimEnd()))
        }
    }

    $stackLines = @($Failure.Text -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 })
    $importantLines = @($stackLines | Where-Object {
        $_ -match '^\s*(Caused by:|Suppressed:)' -or
        $_ -match '(AssertionFailed|AssertionError|Exception|Error)(:|$)'
    } | Select-Object -Unique)
    if ($importantLines.Count -gt 0) {
        $lines.Add('  關鍵診斷：')
        foreach ($line in $importantLines) {
            $lines.Add(('    {0}' -f $line.Trim()))
        }
    }
    if ($stackLines.Count -gt 0) {
        $lines.Add('  Stacktrace：')
        foreach ($line in $stackLines) {
            $lines.Add(('    {0}' -f $line.TrimEnd()))
        }
    }

    $wasTruncated = $Failure.TextTruncated -or $lines.Count -gt $LineLimit
    return Limit-DiagnosticLines `
        -Lines $lines.ToArray() `
        -LineLimit $LineLimit `
        -CharacterLimit $CharacterLimit `
        -OmissionMessage '  … 此失敗的診斷已達上限，其餘內容省略。' `
        -AlreadyTruncated:$wasTruncated
}

function Remove-ConsoleDiffNoise {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [string[]]$Lines
    )

    $filtered = [System.Collections.Generic.List[string]]::new()
    $inDiffBlock = $false
    $suppressedLines = 0

    foreach ($line in $Lines) {
        $payload = if ($null -eq $line) { '' } else { [string]$line }
        if ($payload -match '^\[[A-Z]+\]\s?(.*)$') {
            $payload = $Matches[1]
        }
        $trimmedPayload = $payload.TrimStart()
        $indentation = $payload.Length - $trimmedPayload.Length
        $startsDiffBlock = $trimmedPayload -match '^@@\s+-\d+(?:,\d+)?\s+\+\d+(?:,\d+)?\s+@@'

        if (-not $inDiffBlock -and $startsDiffBlock) {
            $inDiffBlock = $true
            $suppressedLines = 1
            continue
        }
        if ($inDiffBlock) {
            $isDiffContent = -not $trimmedPayload -or
                $indentation -ge 8 -or
                $trimmedPayload -match '^(?:\+|-|\\ No newline at end of file)'
            if ($isDiffContent) {
                $suppressedLines++
                continue
            }
            $filtered.Add(('... Maven plugin diff excerpt omitted ({0} lines).' -f $suppressedLines))
            $inDiffBlock = $false
            $suppressedLines = 0
        }
        $filtered.Add($line)
    }

    if ($inDiffBlock) {
        $filtered.Add(('... Maven plugin diff excerpt omitted ({0} lines).' -f $suppressedLines))
    }
    return ,$filtered.ToArray()
}

function Invoke-QuietMaven {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$SuccessMessage,
        [int]$FailureLineLimit = 120,
        [int]$FailureCharacterLimit = 32768,
        [int]$PerFailureLineLimit = 40,
        [int]$PerFailureCharacterLimit = 12000
    )

    if (-not (Test-Path -LiteralPath $MavenWrapper)) {
        Write-Host "找不到 Maven wrapper：$MavenWrapper"
        return 1
    }

    $effectiveArguments = Add-MavenOutputArguments -Arguments $Arguments
    $projectDirectory = Get-MavenProjectDirectory -Arguments $Arguments
    $reportDirectory = Join-Path $projectDirectory 'target\surefire-reports'
    $reportSnapshot = Get-SurefireReportSnapshot -ReportDirectory $reportDirectory

    $quotedArguments = @($effectiveArguments | ForEach-Object { ConvertTo-CmdArgument $_ })
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $env:ComSpec
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $false
    $startInfo.WorkingDirectory = $MavenRepoRoot
    # Windows PowerShell 5.1 does not expose ProcessStartInfo.ArgumentList. Merge stderr into
    # stdout so one synchronously drained stream cannot deadlock, then retain only its tail.
    $startInfo.Arguments = '/d /s /c ""{0}" {1} 2^>^&1"' -f $MavenWrapper, ($quotedArguments -join ' ')

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $outputLines = [System.Collections.Generic.Queue[string]]::new()
    $capturedCharacters = 0
    $discardedConsoleLines = 0
    $truncatedConsoleLines = 0
    $exitCode = 1

    try {
        if (-not $process.Start()) {
            Write-Host '無法啟動 Maven process。'
            return 1
        }

        while (($line = $process.StandardOutput.ReadLine()) -ne $null) {
            if ($line.Length + 1 -gt $FailureCharacterLimit) {
                $marker = '... [single console line prefix omitted] '
                $retainedLength = [Math]::Max(0, $FailureCharacterLimit - $marker.Length - 1)
                $line = $marker + $line.Substring($line.Length - $retainedLength)
                $truncatedConsoleLines++
            }
            $outputLines.Enqueue($line)
            $capturedCharacters += $line.Length + 1
            while ($outputLines.Count -gt $FailureLineLimit -or
                    ($capturedCharacters -gt $FailureCharacterLimit -and $outputLines.Count -gt 1)) {
                $removed = $outputLines.Dequeue()
                $capturedCharacters -= $removed.Length + 1
                $discardedConsoleLines++
            }
        }
        $process.WaitForExit()
        $exitCode = $process.ExitCode
    } catch {
        Write-Host ("Maven process 執行失敗：{0}" -f $_.Exception.Message)
        return 1
    } finally {
        $stopwatch.Stop()
        $process.Dispose()
    }

    $updatedReports = Get-UpdatedSurefireReports `
        -ReportDirectory $reportDirectory `
        -Snapshot $reportSnapshot
    $summary = Read-SurefireSummary -Reports $updatedReports
    $elapsed = $stopwatch.Elapsed.TotalSeconds

    if ($exitCode -eq 0) {
        Write-Host ('{0}：測試 {1}（略過 {2}），耗時 {3:N1} 秒' -f `
                $SuccessMessage, $summary.Tests, $summary.Skipped, $elapsed)
        return 0
    }

    if ($summary.FailureDetails.Count -gt 0) {
        $diagnosticLines = [System.Collections.Generic.List[string]]::new()
        $diagnosticLines.Add(('Maven 測試失敗：exit {0}，測試 {1}，failure {2}，error {3}，略過 {4}，耗時 {5:N1} 秒' -f `
                $exitCode, $summary.Tests, $summary.Failures, $summary.Errors, $summary.Skipped, $elapsed))
        foreach ($failure in $summary.FailureDetails) {
            if ($diagnosticLines.Count -gt 1) {
                $diagnosticLines.Add('')
            }
            foreach ($line in (Get-FailureDiagnosticLines `
                    -Failure $failure `
                    -LineLimit $PerFailureLineLimit `
                    -CharacterLimit $PerFailureCharacterLimit)) {
                $diagnosticLines.Add($line)
            }
        }
        $boundedDiagnostics = Limit-DiagnosticLines `
            -Lines $diagnosticLines.ToArray() `
            -LineLimit $FailureLineLimit `
            -CharacterLimit $FailureCharacterLimit `
            -OmissionMessage ('… 整體失敗摘要已達上限；本次共有 {0} 個失敗測試。' -f $summary.FailureDetails.Count)
        Write-Host ($boundedDiagnostics -join [Environment]::NewLine)
        return $exitCode
    }

    $fallbackLines = [System.Collections.Generic.List[string]]::new()
    $fallbackLines.Add(('Maven lifecycle 失敗：exit {0}，耗時 {1:N1} 秒；未取得本次失敗測試報告，顯示受限 console 尾端。' -f `
            $exitCode, $elapsed))
    if ($summary.ParseErrors.Count -gt 0) {
        foreach ($parseError in $summary.ParseErrors) {
            $fallbackLines.Add(('Surefire 報告解析失敗：{0}' -f $parseError))
        }
    }
    if ($discardedConsoleLines -gt 0) {
        $fallbackLines.Add(('… 已省略較早的 {0} 行 console 輸出。' -f $discardedConsoleLines))
    }
    if ($truncatedConsoleLines -gt 0) {
        $fallbackLines.Add(('… 已截斷 {0} 個超過字元上限的單一 console 行。' -f $truncatedConsoleLines))
    }
    $filteredConsoleLines = Remove-ConsoleDiffNoise -Lines $outputLines.ToArray()
    foreach ($line in $filteredConsoleLines) {
        if ($line) {
            $fallbackLines.Add($line)
        }
    }
    $boundedFallback = Limit-DiagnosticLines `
        -Lines $fallbackLines.ToArray() `
        -LineLimit $FailureLineLimit `
        -CharacterLimit $FailureCharacterLimit `
        -OmissionMessage '… console fallback 已達整體上限，其餘內容省略。'
    Write-Host ($boundedFallback -join [Environment]::NewLine)
    return $exitCode
}
