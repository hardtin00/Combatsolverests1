param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jbr = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin'
$javac = Join-Path $jbr 'javac.exe'
$java = Join-Path $jbr 'java.exe'
if (-not (Test-Path $javac) -or -not (Test-Path $java)) {
    throw "Java runtime not found: $jbr"
}
$out = Join-Path $root 'build\exhume-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(
    (Join-Path $root 'src\main\java\com\combatsolver1\search\Plan.java'),
    (Join-Path $root 'src\main\java\com\combatsolver1\search\ChoiceProtocol.java'),
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\ExhumeChoiceSafety.java'),
    (Join-Path $PSScriptRoot 'ExhumeExecutionCheck.java')
)
& $javac --release 8 -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "Exhume Java regression compilation failed: $LASTEXITCODE" }
& $java -cp $out com.combatsolver1.runtime.ExhumeExecutionCheck
if ($LASTEXITCODE -ne 0) { throw "Exhume Java regression failed: $LASTEXITCODE" }
