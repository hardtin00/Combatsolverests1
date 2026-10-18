param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jbr = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin'
$javac = Join-Path $jbr 'javac.exe'
$java = Join-Path $jbr 'java.exe'
if (-not (Test-Path $javac) -or -not (Test-Path $java)) { throw "Java runtime not found: $jbr" }
$out = Join-Path $root 'build\resume-save-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\LoadedSaveEvidence.java'),
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\EngineRootStateBinding.java'),
    (Join-Path $PSScriptRoot 'ResumeSaveBindingCheck.java'),
    (Join-Path $PSScriptRoot 'EngineRootStateBindingCheck.java')
)
& $javac --release 8 -Xlint:-options -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "Resume-save binding compile failed: $LASTEXITCODE" }
& $java -cp $out com.combatsolver1.runtime.ResumeSaveBindingCheck
if ($LASTEXITCODE -ne 0) { throw "Resume-save binding regression failed: $LASTEXITCODE" }
& $java -cp $out com.combatsolver1.runtime.EngineRootStateBindingCheck
if ($LASTEXITCODE -ne 0) { throw "Engine root state binding regression failed: $LASTEXITCODE" }
