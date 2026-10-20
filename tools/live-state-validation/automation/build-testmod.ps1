param(
    [Parameter(Mandatory=$true)][string]$GameDir,
    [Parameter(Mandatory=$true)][string]$MtsJar,
    [Parameter(Mandatory=$true)][string]$BaseModJar,
    [string]$Javac = 'javac',
    [string]$JarTool = 'jar'
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$gameJar=Join-Path $GameDir 'desktop-1.0.jar'
$javacCommand=Get-Command $Javac -ErrorAction Stop
$jarCommand=Get-Command $JarTool -ErrorAction Stop
$javacPath=$javacCommand.Source
$jarPath=$jarCommand.Source
foreach($path in @($gameJar,$MtsJar,$BaseModJar,$javacPath,$jarPath)) { if(-not(Test-Path -LiteralPath $path -PathType Leaf)){ throw "required build input is missing: $path" } }
$out=Join-Path $repo 'build\live-state-validation\automation\classes'
$jar=Join-Path $repo 'build\live-state-validation\automation\CS1AutomationTestMod.jar'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$classpath=($gameJar,$MtsJar,$BaseModJar) -join ';'
$source=Join-Path $PSScriptRoot 'CS1AutomationTestMod.java'
& $javacPath --release 8 -Xlint:-options -proc:none -encoding UTF-8 -cp $classpath -d $out $source
if($LASTEXITCODE -ne 0){ throw "automation test mod Java compilation failed: exit=$LASTEXITCODE" }
$classFile=Join-Path $out 'cs1automation\CS1AutomationTestMod.class'
$bytes=[System.IO.File]::ReadAllBytes($classFile); $major=($bytes[6] -shl 8) -bor $bytes[7]
if($major -ne 52){ throw "automation test mod is not Java 8 bytecode: major=$major" }
if(Test-Path -LiteralPath $jar){ Remove-Item -LiteralPath $jar -Force }
& $jarPath cf $jar -C $out cs1automation -C $PSScriptRoot ModTheSpire.json
if($LASTEXITCODE -ne 0){ throw "automation test mod packaging failed: exit=$LASTEXITCODE" }
$hash=(Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash.ToLowerInvariant()
Write-Output "AUTOMATION_TEST_MOD_BUILT path=$jar major=$major sha256=$hash"
