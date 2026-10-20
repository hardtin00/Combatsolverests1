param(
    [Parameter(Mandatory=$true)][string]$GameDir,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AsmJar
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gameJar = Join-Path $GameDir 'desktop-1.0.jar'
$out = Join-Path $root 'build\live-state-validation\audit-classes'
$report = Join-Path $root 'build\live-state-validation\field-audit.txt'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
foreach ($path in @($gameJar,$javac,$java,$AsmJar)) { if (-not (Test-Path $path)) { throw "Required audit input missing: $path" } }
New-Item -ItemType Directory -Force $out | Out-Null
& $javac --release 8 -Xlint:-options -cp $AsmJar -d $out (Join-Path $PSScriptRoot 'FieldBytecodeAudit.java')
if ($LASTEXITCODE -ne 0) { throw "Bytecode audit tool compilation failed: $LASTEXITCODE" }
& $java -cp "$out;$AsmJar" FieldBytecodeAudit $gameJar | Tee-Object -FilePath $report
if ($LASTEXITCODE -ne 0) { throw "Installed-game field audit failed; see $report" }
Write-Output "FIELD_AUDIT_REPORT=$report"
