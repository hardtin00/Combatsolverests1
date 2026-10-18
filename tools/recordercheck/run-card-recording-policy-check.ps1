$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root 'build\card-recording-policy-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$javac = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\javac.exe'
$java = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\java.exe'
& $javac --release 8 -encoding UTF-8 -d $out `
    (Join-Path $root 'src\main\java\com\combatsolver1\runtime\CardActionRecordingPolicy.java') `
    (Join-Path $PSScriptRoot 'CardActionRecordingPolicyCheck.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp $out CardActionRecordingPolicyCheck
exit $LASTEXITCODE
