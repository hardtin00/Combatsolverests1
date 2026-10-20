param(
    [string]$Manifest = (Join-Path $PSScriptRoot '..\..\docs\IRONCLAD_CARD_BATCH_1_MANIFEST.json'),
    [string]$Engine = (Join-Path $PSScriptRoot '..\..\build\engine-mingw-card-batch\combatsolver1-engine.exe')
)
$ErrorActionPreference = 'Stop'
$Engine = [System.IO.Path]::GetFullPath($Engine)
if (-not (Test-Path -LiteralPath $Engine -PathType Leaf)) { throw "Batch engine executable does not exist: $Engine" }
$Engine = (Resolve-Path -LiteralPath $Engine).Path
$engineSha256 = (Get-FileHash -LiteralPath $Engine -Algorithm SHA256).Hash
Write-Output "CARD_BATCH_ENGINE path=$Engine sha256=$engineSha256"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestDoc = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
if ($manifestDoc.schema -ne 'combatsolver1.ironclad-card-batch' -or $manifestDoc.version -ne 1) { throw 'unsupported card manifest schema' }
$javaMap = Get-Content -Raw -LiteralPath (Join-Path $root 'tools\live-state-validation\CardIdWireMap.java')
$cppImporter = Get-Content -Raw -LiteralPath (Join-Path $root 'vendor\sts_lightspeed\apps\live_battle_state.h')
$effectSource = Get-Content -Raw -LiteralPath (Join-Path $root 'vendor\sts_lightspeed\src\combat\BattleContext.cpp')
$seenNative = @{}
$seenEngine = @{}
$rows = [System.Collections.Generic.List[string]]::new()
foreach ($entry in $manifestDoc.entries) {
    if ($seenNative.ContainsKey($entry.native_id) -or $seenEngine.ContainsKey($entry.engine_id)) { throw "duplicate manifest mapping: $($entry.native_id)/$($entry.engine_id)" }
    $seenNative[$entry.native_id] = $true
    $seenEngine[$entry.engine_id] = $true
    $javaPattern = 'm\.put\("' + [regex]::Escape($entry.native_id) + '",\s*"' + [regex]::Escape($entry.engine_id) + '"\)'
    if ($javaMap -notmatch $javaPattern) { throw "Java identity mapping missing: $($entry.native_id) -> $($entry.engine_id)" }
    $cppPattern = 'cardId\s*==\s*sts::CardId::' + [regex]::Escape($entry.engine_id)
    if ($cppImporter -notmatch $cppPattern) { throw "C++ live importer allowlist missing: $($entry.engine_id)" }
    if ($effectSource -notmatch ('case CardId::' + [regex]::Escape($entry.engine_id) + '\s*:')) { throw "C++ effect dispatch missing: $($entry.engine_id)" }
    if ($entry.identity_mapping -ne 'IMPLEMENTED' -or $entry.serialization_recovery -ne 'IMPLEMENTED') { throw "mapping stage is incomplete: $($entry.engine_id)" }
    $rows.Add("| $($entry.native_id) | $($entry.engine_id) | $($entry.cost) / $($entry.upgraded_cost) | $($entry.base_damage) / $($entry.upgraded_damage) | $($entry.target) | $($entry.identity_mapping) | $($entry.serialization_recovery) | $($entry.effect_validation) |")
}
if ($manifestDoc.entries.Count -ne 2) { throw "batch scope changed unexpectedly: expected 2 entries, got $($manifestDoc.entries.Count)" }
$effectOutput = & $Engine card_batch_effect_check 2>&1
$effectOutput | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { throw "C++ card effect regression failed: $($effectOutput -join ' | ')" }
foreach ($entry in $manifestDoc.entries) {
    $effectPattern = '^CARD_BATCH_EFFECT_CHECK_OK card=' + [regex]::Escape($entry.engine_id) + '\b'
    if (-not (@($effectOutput | Where-Object { $_ -match $effectPattern }).Count -eq 1)) {
        throw "C++ effect regression output missing exactly one result for $($entry.engine_id)"
    }
}
$generated = @(
    '# Ironclad live card mapping — batch 1',
    '',
    "Evidence: ``$($manifestDoc.evidence_level)``. This generated report checks the manifest against Java mapping, C++ import allowlist, effect dispatch, and runtime effect regression output.",
    '',
    '| Native ID | Engine ID | Cost base/upgraded | Damage base/upgraded | Target | Identity | Restore | Effect evidence |',
    '|---|---|---:|---:|---|---|---|---|'
) + $rows.ToArray()
$outputPath = Join-Path $root 'build\live-state-validation\card-batch-manifest.generated.md'
New-Item -ItemType Directory -Force (Split-Path -Parent $outputPath) | Out-Null
[System.IO.File]::WriteAllLines($outputPath, $generated, [System.Text.UTF8Encoding]::new($false))
Write-Output "CARD_BATCH_MANIFEST_CHECK_OK entries=$($manifestDoc.entries.Count) effects_runtime=PASS cards=$($manifestDoc.entries.engine_id -join ',') report=$outputPath"
