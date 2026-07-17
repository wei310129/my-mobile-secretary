# Shared Maven runner: keep successful output out of AI context and retain only a bounded failure excerpt.

$MavenRepoRoot = Split-Path -Parent $PSScriptRoot
$MavenWrapper = Join-Path $MavenRepoRoot "mvnw.cmd"

function Invoke-QuietMaven {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$SuccessMessage,
        [int]$FailureLineLimit = 120,
        [int]$FailureCharacterLimit = 32768
    )

    if (-not (Test-Path $MavenWrapper)) {
        Write-Host "Maven wrapper not found: $MavenWrapper" -ForegroundColor Red
        return 1
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $env:ComSpec
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $false
    $startInfo.WorkingDirectory = $MavenRepoRoot
    # Windows PowerShell 5.1 does not expose ProcessStartInfo.ArgumentList.
    # The doubled outer quotes are required by cmd.exe when the batch path is quoted. Merging
    # stderr into stdout lets us drain one stream synchronously without either pipe blocking.
    $startInfo.Arguments = '/d /s /c ""{0}" {1} 2^>^&1"' -f $MavenWrapper, ($Arguments -join ' ')

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $startedAt = [System.Diagnostics.Stopwatch]::StartNew()
    $outputLines = [System.Collections.Generic.Queue[string]]::new()
    $capturedCharacters = 0

    try {
        if (-not $process.Start()) {
            Write-Host "Maven process could not be started." -ForegroundColor Red
            return 1
        }

        while (($line = $process.StandardOutput.ReadLine()) -ne $null) {
            $outputLines.Enqueue($line)
            $capturedCharacters += $line.Length + [Environment]::NewLine.Length
            while ($outputLines.Count -gt $FailureLineLimit -or
                    ($capturedCharacters -gt $FailureCharacterLimit -and $outputLines.Count -gt 1)) {
                $removed = $outputLines.Dequeue()
                $capturedCharacters -= $removed.Length + [Environment]::NewLine.Length
            }
        }
        $process.WaitForExit()
        $exitCode = $process.ExitCode
    } catch {
        Write-Host "Maven runner failed: $($_.Exception.Message)" -ForegroundColor Red
        return 1
    } finally {
        $startedAt.Stop()
        $process.Dispose()
    }

    if ($exitCode -eq 0) {
        Write-Host ("{0} ({1:N1}s)" -f $SuccessMessage, $startedAt.Elapsed.TotalSeconds) -ForegroundColor Green
        return 0
    }

    $excerpt = @($outputLines.ToArray() | Where-Object { $_ -ne '' }) -join [Environment]::NewLine

    Write-Host ("Maven failed with exit code {0} ({1:N1}s). Showing bounded failure output:" -f `
            $exitCode, $startedAt.Elapsed.TotalSeconds) -ForegroundColor Red
    if ($excerpt) {
        Write-Output $excerpt
    }
    return $exitCode
}
