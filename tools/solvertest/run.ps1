# 一键运行离线自检：编译纯模拟层 + 自检工具，然后执行 Solvertest。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1
#   powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command diff -RecordFile C:\Users\me\.combatsolver1\recordings\combat-20261016-120000.jsonl
#   powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command summary
param(
    [string]$Command = "all",
    [string]$RecordFile = ""
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\solvertest'

function Find-Tool([string]$name) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$name.exe") }
    $candidates += "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\$name.exe"
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    $cmd = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "找不到 $name.exe：请安装 JDK 8+ 或设置 JAVA_HOME。"
}

$javac = Find-Tool 'javac'
$java = Find-Tool 'java'

New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @()
$sources += (Get-ChildItem (Join-Path $root 'src\main\java\com\combatsolver1\engine') -Filter *.java).FullName
$sources += (Get-ChildItem (Join-Path $root 'src\main\java\com\combatsolver1\search') -Filter *.java).FullName
$sources += (Get-ChildItem $PSScriptRoot -Filter *.java).FullName

& $javac -encoding UTF-8 -d $out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($Command -eq 'diff') {
    if (-not $RecordFile) {
        $RecordFile = Join-Path $PSScriptRoot 'fixtures\recordings\sample.jsonl'
    }
    & $java -cp $out Solvertest diff $RecordFile
} else {
    & $java -cp $out Solvertest $Command
}
exit $LASTEXITCODE
