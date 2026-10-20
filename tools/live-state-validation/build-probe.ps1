param(
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire',
    [string]$Javac = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\javac.exe',
    [string]$JarTool = 'C:\Program Files\Java\jdk1.6.0_43\bin\jar.exe'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\live-state-validation\classes'
$jar = Join-Path $root 'build\live-state-validation\CS1LiveStateProbe.jar'
if (-not (Test-Path (Join-Path $GameDir 'desktop-1.0.jar'))) { throw 'STS1 desktop-1.0.jar not found' }
if (-not (Test-Path $Javac)) { throw 'Java 8-compatible javac not found' }
if (-not (Test-Path $JarTool)) { throw 'jar tool not found' }
New-Item -ItemType Directory -Force $out | Out-Null
$stubs = @(Get-ChildItem (Join-Path $root 'tools\rng-capture-validation\stubs\com\evacipated') -Recurse -File -Filter *.java | ForEach-Object FullName)
$source = @((Join-Path $PSScriptRoot 'LiveBattleStateProbe.java'), (Join-Path $PSScriptRoot 'CardIdWireMap.java'))
& $Javac --release 8 -proc:none -encoding UTF-8 -cp (Join-Path $GameDir 'desktop-1.0.jar') -d $out ($stubs + $source)
if ($LASTEXITCODE -ne 0) { throw "Probe compilation failed: $LASTEXITCODE" }
$classes = Join-Path $out 'rngprobe\LiveBattleStateProbe.class'
$bytes = [System.IO.File]::ReadAllBytes($classes)
$major = ($bytes[6] -shl 8) -bor $bytes[7]
if ($major -ne 52) { throw "Probe class is not Java 8 bytecode (major=$major)" }
if (Test-Path $jar) { Remove-Item -LiteralPath $jar -Force }
& $JarTool cf $jar -C $out rngprobe -C $PSScriptRoot ModTheSpire.json
if ($LASTEXITCODE -ne 0) { throw "Probe JAR packaging failed: $LASTEXITCODE" }
Write-Output "LIVE_STATE_PROBE_BUILT path=$jar major=$major"
