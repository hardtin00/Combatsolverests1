# W1-d: offline self-check of RngStreams.readFrom reflection (6 assertions, L2).
# Needs the game jars on classpath because RngStreams references AbstractDungeon in read().
param([string]$StsDir = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\rngstreams-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$javac = (Get-Command javac.exe).Source
$java = (Get-Command java.exe).Source
$cp = @((Join-Path $StsDir 'desktop-1.0.jar')) + (Get-ChildItem (Join-Path $StsDir 'mods') -Filter *.jar).FullName
$cpStr = ($cp -join ';')
& $javac --release 8 -encoding UTF-8 -cp $cpStr -d $out `
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\RngStreams.java') `
    (Join-Path $PSScriptRoot 'RngStreamsReadCheck.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp "$out;$cpStr" RngStreamsReadCheck
exit $LASTEXITCODE
