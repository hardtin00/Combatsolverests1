param(
    [string]$StsDir = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire',
    [string]$ModJar = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$steamapps = Split-Path (Split-Path $StsDir -Parent) -Parent
$workshop = Join-Path $steamapps 'workshop\content\646570'
$mts = (Get-ChildItem $workshop -Recurse -Filter ModTheSpire.jar | Select-Object -First 1).FullName
$base = (Get-ChildItem $workshop -Recurse -Filter BaseMod.jar | Select-Object -First 1).FullName
$stslib = (Get-ChildItem $workshop -Recurse -Filter StSLib.jar | Select-Object -First 1).FullName
if (-not $ModJar) { $ModJar = Join-Path $StsDir 'mods\CombatSolver1.jar' }
$game = Join-Path $StsDir 'desktop-1.0.jar'
$jbr = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin'
$javac = Join-Path $jbr 'javac.exe'
if (-not (Test-Path $mts) -or -not (Test-Path $base) -or -not (Test-Path $stslib)) { throw 'MTS, BaseMod, or StSLib jar was not found in the workshop directory.' }
if (-not (Test-Path $ModJar)) { throw "Mod jar not found: $ModJar" }

$classes = Join-Path $root 'build\patchcheck'
New-Item -ItemType Directory -Force $classes | Out-Null
$cp = @($mts, $game, $base, $stslib) -join ';'
& $javac --release 8 -encoding UTF-8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'PatchInjectionCheck.java')
if ($LASTEXITCODE -ne 0) { throw "Patch checker compilation failed: $LASTEXITCODE" }
$gameJava = Join-Path $StsDir 'jre\bin\java.exe'
if (-not (Test-Path $gameJava)) { throw "Game Java 8 runtime not found: $gameJava" }
& $gameJava -cp "$classes;$cp" PatchInjectionCheck $game $mts $base $stslib $ModJar '3.30.3'
if ($LASTEXITCODE -ne 0) { throw "ModTheSpire patch injection check failed: $LASTEXITCODE" }
