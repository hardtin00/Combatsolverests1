param(
    [string]$CMake = 'cmake',
    [string]$Compiler = '',
    [string]$Ninja = '',
    [ValidateRange(1, 16)][int]$Jobs = 2
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$source = Join-Path $root 'vendor/sts_lightspeed'
$out = Join-Path $root 'build/engine'
$configure = @('-S', $source, '-B', $out, '-DCMAKE_BUILD_TYPE=Release')
if ($Compiler) {
    $Compiler = (Resolve-Path -LiteralPath $Compiler).Path
    $env:PATH = (Split-Path $Compiler -Parent) + ';' + $env:PATH
    $configure += "-DCMAKE_CXX_COMPILER=$Compiler"
}
if ($Ninja) { $configure += @('-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$Ninja") }
& $CMake @configure
if ($LASTEXITCODE -ne 0) { throw 'Engine configuration failed' }
& $CMake --build $out --config Release --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Engine compilation failed' }
$exe = Join-Path $out 'combatsolver1-engine.exe'
if (-not (Test-Path $exe)) { $exe = Join-Path $out 'Release/combatsolver1-engine.exe' }
if (-not (Test-Path $exe)) { throw 'Engine executable missing' }
$protocol = & $exe protocol
if ($LASTEXITCODE -ne 0 -or $protocol -notmatch '^COMBATSOLVER1_PROTOCOL version=2 ') {
    throw 'Unexpected engine protocol'
}
$hash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
@("protocol=$protocol", "sha256=$hash", "executable=$exe") | Set-Content -Encoding UTF8 (Join-Path $out 'engine-info.txt')
Write-Output "Engine ready: $exe"
Write-Output "SHA256: $hash"
Write-Output ('Settings entry: engine.path=' + $exe.Replace('\', '/'))
