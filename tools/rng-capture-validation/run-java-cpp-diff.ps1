# W1: TrueRng (Java) vs sts::Random (C++) 18-call bitwise diff.
# Requires: g++ (MinGW) on PATH, JDK 8+ javac/java on PATH.
# The C++ side uses vendored sts_lightspeed/include/game/Random.h (MIT, vendored in repo).
param(
    [string]$Seed0 = "0123456789abcdef",
    [string]$Seed1 = "fedcba9876543210",
    [int]$Counter = 7
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\rng-diff'
New-Item -ItemType Directory -Force -Path $out | Out-Null

$gpp = Get-Command g++.exe -ErrorAction SilentlyContinue
if (-not $gpp) { Write-Error "g++ not found on PATH; C++ side NOT executed. Do not claim a diff result."; exit 3 }
$javac = (Get-Command javac.exe).Source
$java = (Get-Command java.exe).Source

# Compile C++ trace (same program used in the 2026-10-19 L4 validation).
& $gpp.Source -std=c++17 -O0 -I (Join-Path $root 'vendor\sts_lightspeed\include') -I $PSScriptRoot `
    (Join-Path $PSScriptRoot 'cpp_rng_trace.cpp') -o (Join-Path $out 'cpp_rng_trace.exe')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Compile Java side (engine + RngStreamCheck).
$src = @()
$src += (Get-ChildItem (Join-Path $root 'src\main\java\com\combatsolver1\engine') -Filter *.java).FullName
$src += (Get-ChildItem (Join-Path $root 'src\main\java\com\combatsolver1\search') -Filter *.java).FullName
$src += (Join-Path $root 'src\main\java\com\combatsolver1\settings\SolverSettings.java')
$src += (Join-Path $root 'tools\solvertest\RngStreamCheck.java')
& $javac --release 8 -encoding UTF-8 -d (Join-Path $out 'classes') $src
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Produce the two 18-line TSVs with identical inputs.
& $java -cp (Join-Path $out 'classes') RngStreamCheck --seed0 $Seed0 --seed1 $Seed1 --counter $Counter `
    | Where-Object { $_ -match "`t" } | Set-Content (Join-Path $out 'java.tsv')
& (Join-Path $out 'cpp_rng_trace.exe') $Seed0 $Seed1 $Counter | Set-Content (Join-Path $out 'cpp.tsv')

$j = Get-Content (Join-Path $out 'java.tsv')
$c = Get-Content (Join-Path $out 'cpp.tsv')
if ($j.Count -ne 18 -or $c.Count -ne 18) { Write-Error "expected 18 rows, got java=$($j.Count) cpp=$($c.Count)"; exit 2 }
$diff = 0
for ($i = 0; $i -lt 18; $i++) { if ($j[$i] -ne $c[$i]) { $diff++; Write-Output "ROW $i DIFF"; Write-Output "  java: $($j[$i])"; Write-Output "  cpp : $($c[$i])" } }
$first = if ($diff -eq 0) { 'none' } else { 'row(s) above' }
Write-Output "Rows=18 FirstDifference=$first JavaCppMismatch=$diff"
if ($diff -eq 0) { exit 0 } else { exit 1 }
