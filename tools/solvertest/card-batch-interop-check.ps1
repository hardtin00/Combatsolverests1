param(
    [string]$Engine = (Join-Path $PSScriptRoot '..\..\build\engine-mingw-card-batch\combatsolver1-engine.exe'),
    [string]$GameJar = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\desktop-1.0.jar',
    [string]$Javac = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\javac.exe',
    [string]$Java = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\java.exe',
    [switch]$JavaOnly
)
$ErrorActionPreference = 'Stop'
$Engine = [System.IO.Path]::GetFullPath($Engine)
if (-not (Test-Path -LiteralPath $Engine -PathType Leaf)) { throw "Batch engine executable does not exist: $Engine" }
$Engine = (Resolve-Path -LiteralPath $Engine).Path
$engineSha256 = (Get-FileHash -LiteralPath $Engine -Algorithm SHA256).Hash
Write-Output "CARD_BATCH_ENGINE path=$Engine sha256=$engineSha256"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runId = [guid]::NewGuid().ToString('N')
$classDir = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-card-batch-classes-' + $runId)
$stubsDir = Join-Path $root 'tools\rng-capture-validation\stubs\com\evacipated'
$stubs = @(Get-ChildItem $stubsDir -Recurse -File -Filter '*.java' | ForEach-Object FullName)
$sources = $stubs + @(
    (Join-Path $root 'tools\live-state-validation\LiveBattleStateProbe.java'),
    (Join-Path $root 'tools\live-state-validation\CardIdWireMap.java'),
    (Join-Path $root 'tools\live-state-validation\LiveBattleStateV1OfflineProducer.java')
)
& $Javac --release 8 -proc:none -encoding UTF-8 -cp $GameJar -d $classDir $sources
if ($LASTEXITCODE -ne 0) { throw "Java 8 batch interop compilation failed: $LASTEXITCODE" }
$majorBytes = [System.IO.File]::ReadAllBytes((Join-Path $classDir 'rngprobe\CardIdWireMap.class'))
$major = ($majorBytes[6] -shl 8) -bor $majorBytes[7]
if ($major -ne 52) { throw "card map bytecode must be Java 8 (major=$major)" }
$tempBase = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-card-batch-' + [guid]::NewGuid().ToString('N'))
$sourcePath = $tempBase + '-source.json'
$protocolPath = $tempBase + '-protocol.json'
$detailPath = $tempBase + '-detail.txt'
$diffPath = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-card-batch-full-diff-' + $runId + '.txt')
try {
    $protocolFixturePath = Join-Path $root 'tools\solvertest\fixtures\live_battle_state_v1_sentry.json'
    $source = Get-Content -Raw -LiteralPath (Join-Path $root 'tools\solvertest\fixtures\live_battle_cards_v1_source.json') | ConvertFrom-Json
    $piles = @('hand','draw','discard','exhaust')
    foreach ($pile in $piles) { $source.$pile = @() }
    $cards = @(
        @{ native='Cleave'; engine='CLEAVE'; cost=1 },
        @{ native='Bludgeon'; engine='BLUDGEON'; cost=3 }
    )
    $counter = 1
    foreach ($pile in $piles) {
        foreach ($card in $cards) {
            foreach ($upgrade in 0,1) {
                $uuid = 'A0000000-0000-0000-0000-{0:D12}' -f $counter
                $source.$pile += [pscustomobject]@{
                    uuid=$uuid; card_id=$card.native; upgrades=$upgrade; cost=$card.cost; cost_for_turn=$card.cost
                    misc=0
                    free_to_play_once=($card.engine -eq 'BLUDGEON' -and $pile -eq 'draw' -and $upgrade -eq 1)
                    retain=($card.engine -eq 'CLEAVE' -and $pile -eq 'discard' -and $upgrade -eq 1)
                    ethereal=$false
                }
                $counter++
            }
        }
    }
    $source.card_counters.strikes_in_combat = 0
    $batchSourceJson = $source | ConvertTo-Json -Depth 50
    [System.IO.File]::WriteAllText($sourcePath, $batchSourceJson, [System.Text.UTF8Encoding]::new($false))
    & $Java -cp ($classDir + ';' + $GameJar) rngprobe.LiveBattleStateV1OfflineProducer $protocolFixturePath $protocolPath $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Java live-source protocol production failed for batch fixture' }
    $protocol = Get-Content -Raw -LiteralPath $protocolPath | ConvertFrom-Json
    $protocolCardCount = 0
    foreach ($pile in $piles) { $protocolCardCount += $protocol.piles.$pile.Count }
    if ($protocolCardCount -ne 16) { throw "Java protocol serialized $protocolCardCount batch card instances, expected 16" }
    foreach ($pile in $piles) {
        $cleaveCount = @($protocol.piles.$pile | Where-Object { $_.id -eq 'CLEAVE' }).Count
        $bludgeonCount = @($protocol.piles.$pile | Where-Object { $_.id -eq 'BLUDGEON' }).Count
        if (($cleaveCount -ne 2) -or ($bludgeonCount -ne 2)) {
            throw "Java protocol did not serialize both base/upgraded cards in $pile"
        }
        for ($i = 0; $i -lt $source.$pile.Count; $i++) {
            $expected = $source.$pile[$i]
            $actual = $protocol.piles.$pile[$i]
            $expectedEngineId = if ($expected.card_id -eq 'Cleave') { 'CLEAVE' } else { 'BLUDGEON' }
            if ($actual.uuid -ne $expected.uuid -or $actual.id -ne $expectedEngineId -or
                    $actual.upgrade_count -ne $expected.upgrades -or $actual.upgraded -ne ($expected.upgrades -eq 1) -or
                    $actual.special_data -ne 0 -or $actual.cost -ne $expected.cost -or
                    $actual.cost_for_turn -ne $expected.cost_for_turn) {
                throw "Java protocol instance mismatch at piles.$pile[$i] source_uuid=$($expected.uuid)"
            }
        }
    }
    if ($major -ne 52) { throw "Java source serializer bytecode must be Java 8 (major=$major)" }
    $expectedFailures = @(
        @{ name='unknown card ID'; mutate={ param($j) $j.hand[0].card_id='UNKNOWN_CARD' }; reason='unsupported vanilla Ironclad card id: UNKNOWN_CARD' },
        @{ name='Cleave nonzero misc'; mutate={ param($j) $j.hand[0].misc=1 }; reason='unsupported nonzero AbstractCard.misc for Cleave' },
        @{ name='Bludgeon invalid upgrade'; mutate={ param($j) $j.hand[2].upgrades=2 }; reason='unsupported card upgrade count/state: Bludgeon upgrades=2' },
        @{ name='Cleave ethereal'; mutate={ param($j) $j.hand[0].ethereal=$true }; reason='unsupported ethereal state for canonical card mapping: Cleave' },
        @{ name='Bludgeon missing cost'; mutate={ param($j) $j.hand[2].PSObject.Properties.Remove('cost') }; reason='missing or invalid card field: cost' }
    )
    foreach ($case in $expectedFailures) {
        $invalid = $batchSourceJson | ConvertFrom-Json
        & $case.mutate $invalid
        [System.IO.File]::WriteAllText($sourcePath, ($invalid | ConvertTo-Json -Depth 50), [System.Text.UTF8Encoding]::new($false))
        $priorErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $diagnostic = & $Java -cp ($classDir + ';' + $GameJar) rngprobe.LiveBattleStateV1OfflineProducer $protocolFixturePath $protocolPath $sourcePath 2>&1
            $diagnosticExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $priorErrorPreference }
        if ($diagnosticExit -eq 0 -or ($diagnostic -join "`n") -notmatch [regex]::Escape($case.reason)) { throw "source refusal case failed: $($case.name): $($diagnostic -join ' | ')" }
    }
    if ($JavaOnly) {
        Write-Output "CARD_BATCH_JAVA_SERIALIZATION_OK cards=2 instances=$protocolCardCount piles=4 variants=base,upgraded java_class_major=$major source_refusals=$($expectedFailures.Count) evidence=L2_SYNTHETIC_FIXTURE"
        return
    }
    $restored = & $Engine mcts_state $protocolPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "C++ batch protocol restore failed: $($restored -join ' | ')" }
    $text = $restored -join "`n"
    $detailLines = @($restored | Where-Object { $_ -match '^STATE0_DETAIL_CARD ' })
    if ($detailLines.Count -ne 16) { throw "expected 16 restored card instances, got $($detailLines.Count)" }
    if ($text -match 'status=MISMATCH|status=UNAVAILABLE') { throw 'C++ card root readback contains a mismatch or unavailable field' }
    foreach ($line in $detailLines) {
        if ($line -notmatch 'status=MATCH') { throw "card instance did not roundtrip: $line" }
    }
    foreach ($card in $cards) {
        $cardRows = @($detailLines | Where-Object { $_ -match ('normalized_id=' + [regex]::Escape($card.engine) + ' ') })
        foreach ($line in $cardRows) {
            $fieldMatch = [regex]::Match($line, 'pile=([^ ]+) pile_index=(\d+)')
            $instancePile = $fieldMatch.Groups[1].Value
            $instanceIndex = [int]$fieldMatch.Groups[2].Value
            $sourceCard = $protocol.piles.$instancePile[$instanceIndex]
            Write-Output "CARD_BATCH_INSTANCE_DIFF card=$($card.engine) free_to_play_once=$($sourceCard.free_to_play_once) retain=$($sourceCard.retain) $line"
        }
    }
    foreach ($card in $cards) {
        $cardRows = @($detailLines | Where-Object { $_ -match ('normalized_id=' + [regex]::Escape($card.engine) + ' ') })
        if ($cardRows.Count -ne 8) { throw "expected 8 instances over piles/upgrades for $($card.engine), got $($cardRows.Count)" }
        foreach ($pile in $piles) {
            if (@($cardRows | Where-Object { $_ -match ('pile=' + $pile + ' ') }).Count -ne 2) { throw "missing base/upgraded instances for $($card.engine) in $pile" }
        }
    }
    $text | Set-Content -LiteralPath $detailPath -Encoding UTF8
    $diff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $protocolPath -EngineOutputFile $detailPath 2>&1
    if ($LASTEXITCODE -ne 0 -or ($diff -join "`n") -match 'MISMATCH=[1-9]\d*|UNAVAILABLE=[1-9]\d*') { throw "full state fixture diff failed: $($diff -join ' | ')" }
    $diff | Set-Content -LiteralPath $diffPath -Encoding UTF8
    $cxxFailures = @(
        @{ name='unknown protocol card ID'; card_index=0; field='id'; value='UNKNOWN_CARD'; reason='unknown card id: UNKNOWN_CARD' },
        @{ name='Cleave special_data'; card_index=0; field='special_data'; value=1; reason='unsupported card special_data: CLEAVE' },
        @{ name='Bludgeon upgrade state'; card_index=2; field='upgrade_count'; value=2; reason='unsupported card upgrade count/state' },
        @{ name='Bludgeon cost range'; card_index=2; field='cost'; value=128; reason='card cost outside int8 range: BLUDGEON' }
    )
    foreach ($case in $cxxFailures) {
        $invalidProtocol = Get-Content -Raw -LiteralPath $protocolPath | ConvertFrom-Json
        $invalidProtocol.piles.hand[$case.card_index].($case.field) = $case.value
        [System.IO.File]::WriteAllText($sourcePath, ($invalidProtocol | ConvertTo-Json -Depth 50), [System.Text.UTF8Encoding]::new($false))
        $priorErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $diagnostic = & $Engine mcts_state $sourcePath 2>&1
            $diagnosticExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $priorErrorPreference }
        if ($diagnosticExit -ne 3 -or ($diagnostic -join "`n") -notmatch [regex]::Escape($case.reason)) { throw "C++ refusal case failed: $($case.name): $($diagnostic -join ' | ')" }
    }
    Write-Output "CARD_BATCH_INTEROP_OK cards=2 instances=$($detailLines.Count) per_card_upgrade_variants=2 piles=4 full_state_diff=PASS source_refusals=$($expectedFailures.Count) cpp_refusals=$($cxxFailures.Count) java_class_major=$major evidence=L2_SYNTHETIC_FIXTURE"
    Write-Output "CARD_BATCH_INTEROP_DIFF=$diffPath"
} finally {
    Remove-Item -LiteralPath $sourcePath,$protocolPath,($protocolPath + '.capture.json'),$detailPath -Force -ErrorAction SilentlyContinue
}
