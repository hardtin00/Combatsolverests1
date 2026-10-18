param(
    [string]$SourcePath = '',
    [ValidateSet('success', 'failure')][string]$ExpectedResult = 'success',
    [int]$LockMilliseconds = 180
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = if ($SourcePath) { $SourcePath } else { Join-Path $root 'src\main\java\com\combatsolver1\runtime\BattleArtifacts.java' }
$out = Join-Path $root 'build\journal-lock-check'
$run = Join-Path $out ([guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Force -Path $out, $run
$javac = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\javac.exe'
$java = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\java.exe'
& $javac --release 8 -Xlint:-options -encoding UTF-8 -d $out `
    $source `
    (Join-Path $PSScriptRoot 'BattleArtifactsLockCheck.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$ready = Join-Path $run 'ready.txt'
$signal = Join-Path $run 'go.txt'
$stdout = Join-Path $run 'stdout.txt'
$stderr = Join-Path $run 'stderr.txt'
$arguments = @('-cp', "`"$out`"", 'com.combatsolver1.runtime.BattleArtifactsLockCheck',
    "`"$run`"", "`"$ready`"", "`"$signal`"")
$process = Start-Process -FilePath $java -ArgumentList $arguments -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$deadline = [DateTime]::UtcNow.AddSeconds(10)
while (-not (Test-Path -LiteralPath $ready) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 10 }
if (-not (Test-Path -LiteralPath $ready)) { throw 'Java worker did not create its journal' }
$journal = Get-Content -LiteralPath $ready -Raw
$lock = [System.IO.File]::Open($journal, [System.IO.FileMode]::Open,
    [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::Read)
try {
    Set-Content -LiteralPath $signal -Value 'locked' -Encoding ascii
    Start-Sleep -Milliseconds $LockMilliseconds
} finally {
    $lock.Dispose()
}
if (-not $process.WaitForExit(10000)) { $process.Kill(); throw 'Java worker timed out' }
$output = if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Raw } else { '' }
$errors = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Raw } else { '' }
Write-Output ($output + $errors).Trim()
if ($ExpectedResult -eq 'success') {
    if ($output -notmatch 'JOURNAL_REPLACE_OK' -or $output -match 'JOURNAL_REPLACE_FAILED_DIRTY') {
        throw 'Journal transient-lock regression failed.'
    }
} elseif ($output -notmatch 'JOURNAL_REPLACE_FAILED_DIRTY') {
    throw 'Journal persistent-lock failure was not explicitly retained.'
}
