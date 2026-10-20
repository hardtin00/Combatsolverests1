param(
    [Parameter(Mandatory=$true)][string]$GameDir,
    [string]$Javac = 'javac',
    [string]$JarTool = 'jar'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\live-state-validation\classes'
$jar = Join-Path $root 'build\live-state-validation\CS1LiveStateProbe.jar'
$gameJar=Join-Path $GameDir 'desktop-1.0.jar'
$javacCommand=Get-Command $Javac -ErrorAction Stop
$jarCommand=Get-Command $JarTool -ErrorAction Stop
$javacPath=$javacCommand.Source
$jarPath=$jarCommand.Source
foreach($path in @($gameJar,$javacPath,$jarPath)) { if(-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "required probe build input is missing: $path" } }
New-Item -ItemType Directory -Force $out | Out-Null
$stubs = @(Get-ChildItem (Join-Path $root 'tools\rng-capture-validation\stubs\com\evacipated') -Recurse -File -Filter *.java | ForEach-Object FullName)
$source = @((Join-Path $PSScriptRoot 'LiveBattleStateProbe.java'), (Join-Path $PSScriptRoot 'CardIdWireMap.java'))
& $javacPath --release 8 -proc:none -encoding UTF-8 -cp $gameJar -d $out ($stubs + $source)
if ($LASTEXITCODE -ne 0) { throw "Probe compilation failed: $LASTEXITCODE" }
$classes = Join-Path $out 'rngprobe\LiveBattleStateProbe.class'
$bytes = [System.IO.File]::ReadAllBytes($classes)
$major = ($bytes[6] -shl 8) -bor $bytes[7]
if ($major -ne 52) { throw "Probe class is not Java 8 bytecode (major=$major)" }
if (Test-Path $jar) { Remove-Item -LiteralPath $jar -Force }
& $jarPath cf $jar -C $out rngprobe -C $PSScriptRoot ModTheSpire.json
if ($LASTEXITCODE -ne 0) { throw "Probe JAR packaging failed: $LASTEXITCODE" }
Write-Output "LIVE_STATE_PROBE_BUILT path=$jar major=$major"
