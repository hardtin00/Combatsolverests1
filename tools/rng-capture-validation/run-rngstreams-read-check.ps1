# W1-d: offline self-check of RngStreams.readFrom reflection (6 assertions, L2).
# Needs the game jars on classpath because RngStreams references AbstractDungeon in read().
# Cross-platform: runs on Linux pwsh (CI) and Windows PowerShell.
param([string]$StsDir = 'C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$out = Join-Path $root 'build/rngstreams-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
# Resolve javac/java without the Windows-only .exe suffix so the same script works on Linux pwsh.
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
$cp = @((Join-Path $StsDir 'desktop-1.0.jar')) + (Get-ChildItem (Join-Path $StsDir 'mods') -Filter *.jar).FullName
# Windows PowerShell 5.1 has no $IsWindows; PS7+ sets it. Classpath separator is ';' on Windows, ':' on Linux/macOS.
$isWin = if ($null -ne $IsWindows) { $IsWindows } else { $true }
$sep = if ($isWin) { ';' } else { ':' }
$cpStr = ($cp -join $sep)
# -source/-target 8 works on both JDK 8 (CI) and JDK 17 (local); --release 8 is JDK 9+ only.
& $javac -source 8 -target 8 -encoding UTF-8 -cp $cpStr -d $out `
    (Join-Path $root 'src/main/java/com/combatsolver1/runtime/RngStreams.java') `
    (Join-Path $PSScriptRoot 'RngStreamsReadCheck.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp ($out + $sep + $cpStr) RngStreamsReadCheck
exit $LASTEXITCODE
