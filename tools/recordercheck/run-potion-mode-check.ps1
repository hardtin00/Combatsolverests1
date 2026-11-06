# W4-a: engine-path potion mode / PLANPOTION / USE_POTION protocol self-check (11 assertions, L2).
# Pure Java, no game jars needed.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\potion-mode-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$javac = (Get-Command javac.exe).Source
$java = (Get-Command java.exe).Source
& $javac --release 8 -encoding UTF-8 -d $out `
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\EngineProtocolFields.java') `
    (Join-Path $PSScriptRoot 'PotionModeCheck.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp $out com.combatsolver1.runtime.PotionModeCheck
exit $LASTEXITCODE
