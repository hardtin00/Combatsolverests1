# 从当前提交构建完整 Mod、打包，并把 commit 与文件指纹记录下来。
#
# 为什么不用 CI 直接构建：完整构建需要《杀戮尖塔》本体与 ModTheSpire / BaseMod 的 jar，
# 它们有版权、不能放进仓库，所以放在本机执行；CI 只跑不依赖游戏文件的离线自检。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1
#   powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1 -StsDir "D:\Steam\steamapps\common\SlayTheSpire"
#   powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1 -Deploy     # 额外装进游戏 mods/
param(
    [string]$StsDir = $env:STS_DIR,
    [switch]$Deploy
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

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

if (-not $StsDir) {
    foreach ($guess in @(
        'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire',
        'D:\Steam\steamapps\common\SlayTheSpire',
        'E:\Steam\steamapps\common\SlayTheSpire')) {
        if (Test-Path (Join-Path $guess 'desktop-1.0.jar')) { $StsDir = $guess; break }
    }
}
if (-not $StsDir -or -not (Test-Path (Join-Path $StsDir 'desktop-1.0.jar'))) {
    throw "找不到游戏本体：用 -StsDir 指定，或设置环境变量 STS_DIR。"
}

# ModTheSpire / BaseMod 通常来自创意工坊（<Steam>/steamapps/workshop/content/646570）：
# 从游戏目录逐级向上找，兼容 steamapps 在游戏目录上两层的常见布局
$workshop = $null
$ancestor = $StsDir
for ($i = 0; $i -lt 4 -and $ancestor; $i++) {
    foreach ($suffix in @('steamapps\workshop\content\646570', 'workshop\content\646570')) {
        $candidate = Join-Path $ancestor $suffix
        if (Test-Path $candidate) { $workshop = $candidate; break }
    }
    if ($workshop) { break }
    $ancestor = Split-Path $ancestor -Parent
}
if (-not $workshop) { throw "找不到创意工坊目录（在 $StsDir 及其上级中找 steamapps\workshop\content\646570）。" }
$mts = (Get-ChildItem $workshop -Recurse -Filter 'ModTheSpire.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$base = (Get-ChildItem $workshop -Recurse -Filter 'BaseMod.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (-not $mts -or -not $base) { throw "找不到 ModTheSpire.jar / BaseMod.jar（创意工坊目录：$workshop）。" }

$classpath = @((Join-Path $StsDir 'desktop-1.0.jar'), $mts, $base) -join ';'
$classes = Join-Path $root 'build\classes'
$libs = Join-Path $root 'build\libs'
Remove-Item -Recurse -Force $classes, $libs -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libs | Out-Null

$javac = Find-Tool 'javac'
$sources = (Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java).FullName
# 必须锁定 Java 8 字节码：游戏跑在 Java 8 上，用高版本 JDK 编译出的 class（major 65）
# 游戏根本加载不了，表现为"游戏打不开"。这里用 --release 8 并在下面校验版本号。
& $javac --release 8 -g:source,lines,vars -encoding UTF-8 -proc:none -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "完整 Mod 编译失败（$LASTEXITCODE）。" }

$probe = Join-Path $classes 'com\combatsolver1\CombatSolver1Mod.class'
$bytes = [System.IO.File]::ReadAllBytes($probe)
$major = ($bytes[6] -shl 8) -bor $bytes[7]
if ($major -ne 52) {
    throw "字节码版本不对：major=$major（应为 52 = Java 8）。游戏装不上这样的 class，请检查 javac 的 --release 参数。"
}
Write-Output "字节码版本校验通过：major=$major（Java 8）。"

Copy-Item (Join-Path $root 'src\main\resources\ModTheSpire.json') (Join-Path $classes 'ModTheSpire.json') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = Join-Path $libs 'CombatSolver1.jar'
# 不能用 CreateFromDirectory：PowerShell 5.1 的 .NET Framework 会把条目名写成
# "com\combatsolver1\.."（反斜杠），而 ZIP 规范与 Java 类加载器要求正斜杠，
# 这样的 jar 游戏加载不了。这里逐个创建条目并强制用正斜杠。
$zip = [System.IO.Compression.ZipFile]::Open($jar, 'Create')
try {
    Get-ChildItem $classes -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($classes.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel)
    }
} finally {
    $zip.Dispose()
}
$verify = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $bad = @($verify.Entries | Where-Object { $_.FullName.Contains('\') })
    if ($bad.Count -gt 0) {
        throw "jar 内有 " + $bad.Count + " 个条目名含反斜杠，Java 无法加载。"
    }
    Write-Output ("条目校验通过：" + $verify.Entries.Count + " 个条目，全部使用正斜杠。")
} finally {
    $verify.Dispose()
}

$commit = (& git -C $root rev-parse HEAD).Trim()
$dirty = (& git -C $root status --porcelain) -join ''
$manifest = [System.IO.File]::ReadAllText((Join-Path $root 'src\main\resources\ModTheSpire.json'))
$version = ([regex]::Match($manifest, '"version"\s*:\s*"([^"]+)"')).Groups[1].Value
$sha = (Get-FileHash $jar -Algorithm SHA256).Hash

$dirtyNote = if ($dirty) { "  （工作区有未提交改动，发布前请先提交）" } else { "" }
$builtAt = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$info = @(
    "file    : $jar",
    "version : $version",
    ("commit  : " + $commit + $dirtyNote),
    "sha256  : $sha",
    "built   : $builtAt",
    "game    : $StsDir"
) -join "`n"
Write-Output $info
[System.IO.File]::WriteAllText((Join-Path $libs 'release-info.txt'), $info, (New-Object System.Text.UTF8Encoding($false)))

if ($Deploy) {
    $mods = Join-Path $StsDir 'mods'
    $backup = Join-Path $mods ("CombatSolver1.jar.bak-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    if (Test-Path (Join-Path $mods 'CombatSolver1.jar')) {
        Copy-Item -LiteralPath (Join-Path $mods 'CombatSolver1.jar') -Destination $backup -Force
    }
    Copy-Item -LiteralPath $jar -Destination (Join-Path $mods 'CombatSolver1.jar') -Force
    Write-Output ("已部署到 " + $mods + "（旧版备份：" + $backup + "）")
}
