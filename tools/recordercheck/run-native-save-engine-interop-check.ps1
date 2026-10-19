param(
    [Parameter(Mandatory=$true)][string]$SavePath,
    [Parameter(Mandatory=$true)][string]$ExpectedStatePrefix,
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire',
    [long]$Simulations = 100000
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$save = (Resolve-Path $SavePath).Path
$engine = Join-Path $root 'build\engine\combatsolver1-engine.exe'
$desktopJar = Join-Path $GameDir 'desktop-1.0.jar'
$gameJava = Join-Path $GameDir 'jre\bin\java.exe'
$jbr = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin'
$javac = Join-Path $jbr 'javac.exe'
$java = Join-Path $jbr 'java.exe'
foreach ($path in @($save, $engine, $desktopJar, $gameJava, $javac, $java)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required interop test input missing: $path" }
}
$out = Join-Path $root 'build\native-save-engine-interop-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\NativeReadEvidence.java'),
    (Join-Path $PSScriptRoot 'NativeSaveEngineInteropCheck.java')
)
& $javac --release 8 -Xlint:-options -encoding UTF-8 -cp $desktopJar -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "Interop regression compile failed: $LASTEXITCODE" }
$expected = $ExpectedStatePrefix
& $gameJava -cp "$out;$desktopJar" com.combatsolver1.runtime.NativeSaveEngineInteropCheck `
    $save $engine $out 'com.megacrit.cardcrawl.rooms.MonsterRoomElite' $Simulations $expected
if ($LASTEXITCODE -ne 0) { throw "Native-save engine interop regression failed: $LASTEXITCODE" }
