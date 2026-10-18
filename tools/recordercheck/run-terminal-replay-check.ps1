param(
    [Parameter(Mandatory = $true)]
    [string]$BundlePath,
    [string]$EnginePath = ""
)

# Usage: powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-terminal-replay-check.ps1 -BundlePath <battle-bundle> [-EnginePath <engine.exe>]

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\terminalreplaycheck'

function Find-Tool([string]$name) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$name.exe") }
    $candidates += "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\$name.exe"
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    $command = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Missing $name.exe. Install JDK 8+ or set JAVA_HOME."
}

$javac = Find-Tool 'javac'
$java = Find-Tool 'java'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $javac -encoding UTF-8 -Xlint:-options -source 8 -target 8 -d $out `
    (Join-Path $root 'tools\solvertest\Json.java') `
    (Join-Path $PSScriptRoot 'TerminalReplayCheck.java') `
    (Join-Path $PSScriptRoot 'TerminalReplayCheckTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& $java -cp $out TerminalReplayCheckTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$arguments = @('-cp', $out, 'TerminalReplayCheck', (Resolve-Path $BundlePath).Path)
if ($EnginePath) { $arguments += (Resolve-Path $EnginePath).Path }
& $java @arguments
exit $LASTEXITCODE
