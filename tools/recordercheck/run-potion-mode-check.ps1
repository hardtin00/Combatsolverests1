# W4-a: engine-path potion mode / PLANPOTION / USE_POTION protocol self-check (11 assertions, L2).
# Pure Java, no game jars needed. Cross-platform: runs on Linux pwsh (CI) and Windows PowerShell.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$out = Join-Path $root 'build/potion-mode-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
# Resolve javac/java without the Windows-only .exe suffix so the same script works on Linux pwsh.
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
# -source/-target 8 works on both JDK 8 (CI) and JDK 17 (local); --release 8 is JDK 9+ only.
& $javac -source 8 -target 8 -encoding UTF-8 -d $out `
    (Join-Path $root 'src/main/java/com/combatsolver1/runtime/EngineProtocolFields.java') `
    (Join-Path $PSScriptRoot 'PotionModeCheck.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp $out com.combatsolver1.runtime.PotionModeCheck
exit $LASTEXITCODE
