param([string]$Engine = '')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Engine) { $Engine = Join-Path $root 'build\engine\combatsolver1-engine.exe' }
$fixture = Join-Path $PSScriptRoot 'fixtures\live_battle_state_v1_sentry.json'
$gameJar = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\desktop-1.0.jar'
$javac = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\javac.exe'
$java = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\jbr\bin\java.exe'
$classes = Join-Path $root 'build\live-state-validation\interop-classes'
$generated = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-v1-java-' + [guid]::NewGuid().ToString('N') + '.json')
New-Item -ItemType Directory -Force $classes | Out-Null
$stubs = @(Get-ChildItem (Join-Path $root 'tools\rng-capture-validation\stubs\com\evacipated') -Recurse -File -Filter *.java | ForEach-Object FullName)
$javaSources = $stubs + @(
    (Join-Path $PSScriptRoot '..\live-state-validation\LiveBattleStateProbe.java'),
    (Join-Path $PSScriptRoot '..\live-state-validation\CardIdWireMap.java'),
    (Join-Path $PSScriptRoot '..\live-state-validation\LiveBattleStateV1OfflineProducer.java')
)
& $javac --release 8 -proc:none -encoding UTF-8 -cp $gameJar -d $classes $javaSources
if ($LASTEXITCODE -ne 0) { throw "Java 8 protocol producer compilation failed: $LASTEXITCODE" }
$classMajors = @{}
foreach ($className in @('LiveBattleStateProbe', 'CardIdWireMap', 'LiveBattleStateV1OfflineProducer')) {
    $classBytes = [System.IO.File]::ReadAllBytes((Join-Path $classes ('rngprobe\' + $className + '.class')))
    $classMajor = ($classBytes[6] -shl 8) -bor $classBytes[7]
    $classMajors[$className] = $classMajor
    if ($classMajor -ne 52) { throw "$className bytecode is not Java 8 (major=$classMajor)" }
}
$major = $classMajors['LiveBattleStateProbe']
& $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $generated
if ($LASTEXITCODE -ne 0) { throw "Java protocol serialization failed: $LASTEXITCODE" }
$sourceBuilt = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-source-assembly-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $sourceProducer = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer --mock-live-source $fixture $sourceBuilt 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java live-source assembly mock failed: $($sourceProducer -join ' | ')" }
    $sourceRestored = & $Engine mcts_state $sourceBuilt 2>&1
    $sourceRestoredText = $sourceRestored -join "`n"
    if ($LASTEXITCODE -ne 0 -or $sourceRestoredText -notmatch 'STATE0 hp=70 max=80 blk=0 en=3 turn=1' -or
            $sourceRestoredText -notmatch 'path=player.hp source=70 restored=70 status=MATCH' -or
            $sourceRestoredText -notmatch 'path=act source=1 restored=1 status=MATCH' -or
            $sourceRestoredText -match 'status=MISMATCH|status=UNAVAILABLE') {
        throw "Java live-source assembly mock did not restore through C++: $sourceRestoredText"
    }
    $sourceEngineDetail = Join-Path $root 'build\live-state-validation\source-assembler-engine-detail.txt'
    $sourceRestoredText | Set-Content -LiteralPath $sourceEngineDetail -Encoding UTF8
    $sourceDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $sourceBuilt -EngineOutputFile $sourceEngineDetail 2>&1
    if ($LASTEXITCODE -ne 0 -or ($sourceDiff -join "`n") -match 'MISMATCH=[1-9]\d*|UNAVAILABLE=[1-9]\d*') {
        throw "Java source assembler mock full-path diff failed: $($sourceDiff -join ' | ')"
    }
    $sourceDiff | Set-Content -LiteralPath (Join-Path $root 'build\live-state-validation\source-assembler-diff.txt') -Encoding UTF8
    Write-Output 'LIVE_STATE_V1_SOURCE_ASSEMBLER evidence=OFFLINE_MOCK_SOURCE_NOT_REAL_GAME_STATE root=PASS'

    $eventFixture = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-v1-event-fixture-' + [guid]::NewGuid().ToString('N') + '.json')
    $eventBuilt = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-v1-event-source-' + [guid]::NewGuid().ToString('N') + '.json')
    try {
        $eventJson = Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json
        $eventJson.room_context.kind = 'EVENT'
        $eventJson.room_context.class = 'com.megacrit.cardcrawl.rooms.EventRoom'
        $eventJson.room_context.event_class = 'com.megacrit.cardcrawl.events.exordium.DeadAdventurer'
        $eventJson.room_context.event_encounter = '3 Sentries'
        $eventJson.room_context.event_enemy_index = 0
        $eventJson | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $eventFixture -Encoding UTF8
        $eventProducer = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer --mock-live-source $eventFixture $eventBuilt 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Java EventRoom fixture serialization failed: $($eventProducer -join ' | ')" }
        $eventRestored = & $Engine mcts_state $eventBuilt 2>&1
        $eventRestoredText = $eventRestored -join "`n"
        if ($LASTEXITCODE -ne 0 -or $eventRestoredText -notmatch 'LIVE_BATTLE_ORIGIN origin=DEAD_ADVENTURER_EVENT search_scope=COMBAT_ONLY' -or
                $eventRestoredText -notmatch 'path=room_context.kind source="EVENT" restored="EVENT" status=MATCH' -or
                $eventRestoredText -notmatch 'path=room_context.class source="com.megacrit.cardcrawl.rooms.EventRoom" restored="com.megacrit.cardcrawl.rooms.EventRoom" status=MATCH' -or
                $eventRestoredText -notmatch 'path=room_context.event_enemy_index source=0 restored=0 status=MATCH' -or
                $eventRestoredText -match 'status=MISMATCH|status=UNAVAILABLE') {
            throw "Java EventRoom source fixture did not restore with event provenance: $eventRestoredText"
        }
        $eventDetail = Join-Path $root 'build\live-state-validation\event-source-assembler-engine-detail.txt'
        $eventRestoredText | Set-Content -LiteralPath $eventDetail -Encoding UTF8
        $eventDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $eventBuilt -EngineOutputFile $eventDetail 2>&1
        if ($LASTEXITCODE -ne 0 -or ($eventDiff -join "`n") -match 'MISMATCH=[1-9]\d*|UNAVAILABLE=[1-9]\d*') {
            throw "Java EventRoom fixture full-path diff failed: $($eventDiff -join ' | ')"
        }
        $eventDiffReportPath = Join-Path (Split-Path -Parent $eventDetail) 'live-state-v1-full-field-diff.txt'
        $eventDiffReport = Get-Content -Raw -LiteralPath $eventDiffReportPath
        if ($eventDiffReport -match '(?m)^\| `\$\.[^`]+` \| REQUIRED \| \*\*(?!MATCH\*\*)') {
            throw 'a REQUIRED EventRoom fixture path was not matched'
        }
        Copy-Item -LiteralPath $eventBuilt -Destination (Join-Path $root 'build\live-state-validation\event-source-assembler-state.json') -Force
        Copy-Item -LiteralPath $eventDiffReportPath -Destination (Join-Path $root 'build\live-state-validation\event-source-assembler-full-field-diff.txt') -Force
        $eventDiff | Set-Content -LiteralPath (Join-Path $root 'build\live-state-validation\event-source-assembler-diff.txt') -Encoding UTF8
        $eventSearch = & $Engine mcts_state_search $eventBuilt 16 2>&1
        $eventSearchText = $eventSearch -join "`n"
        if ($LASTEXITCODE -ne 0 -or $eventSearchText -notmatch 'LIVE_BATTLE_SEARCH_RESULT scope=COMBAT_ONLY origin=DEAD_ADVENTURER_EVENT' -or
                $eventSearchText -notmatch 'event_callback=NOT_INVOKED') {
            throw "EventRoom battle-only search did not terminate through the isolated battle search entry: $eventSearchText"
        }
        $eventSearchText | Set-Content -LiteralPath (Join-Path $root 'build\live-state-validation\event-source-assembler-search.txt') -Encoding UTF8
        Write-Output 'LIVE_STATE_V1_EVENT_SOURCE evidence=OFFLINE_MOCK_SOURCE_NOT_REAL_GAME_STATE root=PASS diff=PASS search_scope=COMBAT_ONLY'
    } finally {
        Remove-Item -LiteralPath $eventFixture -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $eventBuilt -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ($eventBuilt + '.capture.json') -Force -ErrorAction SilentlyContinue
    }
} finally {
    Remove-Item -LiteralPath $sourceBuilt -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($sourceBuilt + '.capture.json') -Force -ErrorAction SilentlyContinue
}
$captureManifest = Get-Content -Raw -LiteralPath ($generated + '.capture.json') | ConvertFrom-Json
$snapshotHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $generated).Hash.ToLowerInvariant()
$fixtureCaptureId = (Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json).capture_id
if ($captureManifest.capture_id -ne $fixtureCaptureId -or $captureManifest.snapshot_sha256 -ne $snapshotHash) {
    throw 'capture manifest is not bound to the serialized protocol bytes and capture UUID'
}
$valid = & $Engine mcts_state $generated 2>&1
$validText = $valid -join "`n"
        if ($LASTEXITCODE -ne 0 -or $validText -notmatch 'STATE0 hp=70 max=80 blk=0 en=3 turn=1' -or $validText -notmatch 'LIVE_BATTLE_ORIGIN origin=MAP_MONSTER_ELITE search_scope=COMBAT_ONLY' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=room_context.kind source="MAP_MONSTER" restored="MAP_MONSTER" status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=player.hp source=70 restored=70 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=player.max_hp source=80 restored=80 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=player.block source=0 restored=0 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=player.energy source=3 restored=3 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=player.energy_per_turn source=3 restored=3 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=turn source=1 restored=1 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=stage source="PLAYER_NORMAL" restored="PLAYER_NORMAL" status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=queues_empty source=true restored=true status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=act source=1 restored=1 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=capture_id .* status=NOT_APPLICABLE' -or $validText -notmatch 'STATE0_DETAIL_CARD uuid=00000000-0000-0000-0000-000000000001 dense_id=0 pile=hand pile_index=0 source_id=STRIKE_RED normalized_id=Strike_R status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=player.statuses .* status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=monsters\[0\].statuses .* status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=relic_counters .* status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_FIELD path=potions .* status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_PILE path=piles.draw source_count=1 restored_count=1 status=MATCH' -or $validText -notmatch 'STATE0_DETAIL_CARD uuid=00000000-0000-0000-0000-000000000003 dense_id=2 pile=discard pile_index=0 source_id=RITUAL_DAGGER normalized_id=RitualDagger status=MATCH' -or $validText -notmatch 'LIVE_STATE_INTERNAL loop_count=0 energy_wasted=0 cards_drawn=0 monster_turn_idx=6 stolen_gold_check=0') {
    throw "Java-generated offline fixture rejected or wrong root: $($valid -join ' | ')"
}
if ($validText -notmatch 'player\.card_draw_per_turn source=5 restored=5 status=MATCH') {
    throw 'card_draw_per_turn did not round-trip from restored Player'
}
$noTarget = Get-Content -Raw -LiteralPath $generated | ConvertFrom-Json
$noTarget.player.PSObject.Properties.Remove('last_targeted_monster')
$noTargetFile = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-no-target-' + [guid]::NewGuid().ToString('N') + '.json')
[System.IO.File]::WriteAllText($noTargetFile, ($noTarget | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
try {
    $noTargetOutput = & $Engine mcts_state $noTargetFile 2>&1
    $noTargetText = $noTargetOutput -join "`n"
    if ($LASTEXITCODE -ne 0 -or $noTargetText -notmatch 'path=player\.last_targeted_monster .*status=NOT_APPLICABLE') {
        throw "C++ did not classify absent native target state as scenario-internal: $noTargetText"
    }
    $surrounded = Get-Content -Raw -LiteralPath $noTargetFile | ConvertFrom-Json
    $surrounded.player.statuses = @($surrounded.player.statuses) + @([pscustomobject]@{ id='SURROUNDED'; amount=1 })
    [System.IO.File]::WriteAllText($noTargetFile, ($surrounded | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $surroundedOutput = & $Engine mcts_state $noTargetFile 2>&1
    $ErrorActionPreference = $savedPreference
    if ($LASTEXITCODE -eq 0 -or ($surroundedOutput -join "`n") -notmatch 'SURROUNDED requires last_targeted_monster from a trusted source') {
        throw "C++ accepted SURROUNDED without a trusted target source: $($surroundedOutput -join ' | ')"
    }
    $LASTEXITCODE = 0
    Write-Output 'LIVE_STATE_CONDITIONAL_TARGET evidence=OFFLINE_FIXTURE_NOT_REAL_GAME_STATE omitted_without_surrounded=PASS missing_with_surrounded=REJECTED'
} finally { Remove-Item -LiteralPath $noTargetFile -Force -ErrorAction SilentlyContinue }
$cursorCheck = & $Engine live_state_cursor_check $generated 2>&1
if ($LASTEXITCODE -ne 0 -or ($cursorCheck -join "`n") -notmatch 'LIVE_STATE_CURSOR_CHECK_OK root=6 alternate_terminal=3 reset=0 slot_order=0,1,2 sentry_state=equal') {
    throw "player-boundary cursor reset changed Sentry traversal semantics: $($cursorCheck -join ' | ')"
}
$timingCheck = & $Engine live_state_power_timing_check 2>&1
if ($LASTEXITCODE -ne 0 -or ($timingCheck -join "`n") -notmatch 'LIVE_STATE_POWER_TIMING_CHECK_OK powers=DOUBLE_DAMAGE,FRAIL,VULNERABLE,WEAK,DRAW_REDUCTION applied_and_expired_cases=10 doubt_weak_cases=5') {
    throw "player power just-applied timing transitions failed: $($timingCheck -join ' | ')"
}
$actVariant = Get-Content -Raw -LiteralPath $generated | ConvertFrom-Json
$actVariant.act = 3
$actTemp = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-act-' + [guid]::NewGuid().ToString('N') + '.json')
[System.IO.File]::WriteAllText($actTemp, ($actVariant | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
$actOutput = & $Engine mcts_state $actTemp 2>&1
Remove-Item -LiteralPath $actTemp -Force -ErrorAction SilentlyContinue
if ($LASTEXITCODE -ne 0 -or ($actOutput -join "`n") -notmatch 'STATE0_DETAIL_FIELD path=act source=3 restored=3 status=MATCH') {
    throw "act did not round-trip from restored BattleContext: $($actOutput -join ' | ')"
}
$playerVariant = Get-Content -Raw -LiteralPath $generated | ConvertFrom-Json
$playerVariant.player.hp = 61
$playerVariant.player.max_hp = 91
$playerVariant.player.block = 7
$playerVariant.player.energy = 2
$playerVariant.player.energy_per_turn = 4
$playerVariant.player.card_draw_per_turn = 6
$playerVariant.turn = 2
$playerTemp = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-player-' + [guid]::NewGuid().ToString('N') + '.json')
[System.IO.File]::WriteAllText($playerTemp, ($playerVariant | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
$playerOutput = & $Engine mcts_state $playerTemp 2>&1
Remove-Item -LiteralPath $playerTemp -Force -ErrorAction SilentlyContinue
$playerText = $playerOutput -join "`n"
if ($LASTEXITCODE -ne 0 -or $playerText -notmatch 'player\.hp source=61 restored=61 status=MATCH' -or
        $playerText -notmatch 'player\.max_hp source=91 restored=91 status=MATCH' -or
        $playerText -notmatch 'player\.block source=7 restored=7 status=MATCH' -or
        $playerText -notmatch 'player\.energy source=2 restored=2 status=MATCH' -or
        $playerText -notmatch 'player\.energy_per_turn source=4 restored=4 status=MATCH' -or
        $playerText -notmatch 'player\.card_draw_per_turn source=6 restored=6 status=MATCH' -or
        $playerText -notmatch 'path=turn source=2 restored=2 status=MATCH') {
    throw "changed player fields were not read back from restored BattleContext: $($playerOutput -join ' | ')"
}
$diffPath = Join-Path $root 'build\live-state-validation\offline-interop-diff.txt'
$engineOutputFile = Join-Path $root 'build\live-state-validation\engine-detail.txt'
$validText | Set-Content -LiteralPath $engineOutputFile -Encoding UTF8
$pathDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $generated -EngineOutputFile $engineOutputFile 2>&1
if ($LASTEXITCODE -ne 0) { throw "full path diff failed: $($pathDiff -join ' | ')" }
$pathDiff | Write-Output
@($pathDiff; $validText) | Set-Content -LiteralPath $diffPath -Encoding UTF8
$pathDiffText = $pathDiff -join "`n"
if ($pathDiffText -match 'MISMATCH=([1-9]\d*)') { throw 'offline root had a field mismatch' }
if ($pathDiffText -notmatch 'paths=149 MATCH=148 MISMATCH=0 UNAVAILABLE=0 NOT_APPLICABLE=1 classifications=CONDITIONAL=35,DERIVABLE=14,REQUIRED=99,UNUSED=1 first_difference=NONE') {
    throw "canonical protocol field inventory is incomplete or includes non-canonical audit rows: $pathDiffText"
}
Write-Output "JAVA_CPP_INTEROP evidence=OFFLINE_FIXTURE_NOT_REAL_GAME_STATE java_major=$major root=PASS cards=PASS"
$cardSourceFixture = Join-Path $PSScriptRoot 'fixtures\live_battle_cards_v1_source.json'
$cardInteropFile = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-card-v1-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $cardProduced = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $cardInteropFile $cardSourceFixture 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java source-card serialization failed: $($cardProduced -join ' | ')" }
    $cardOutput = & $Engine mcts_state $cardInteropFile 2>&1
    $cardOutputText = $cardOutput -join "`n"
    $cardMatchLines = @([regex]::Matches($cardOutputText, '(?m)^STATE0_DETAIL_CARD .* status=MATCH(?:\s|$)'))
    $cardsOk = $LASTEXITCODE -eq 0
    $cardsOk = $cardsOk -and $cardOutputText -notmatch 'status=MISMATCH|status=UNAVAILABLE'
    $cardsOk = $cardsOk -and $cardOutputText -match 'uuid=10000000-0000-0000-0000-000000000001 dense_id=0 pile=hand pile_index=0 source_id=STRIKE_RED normalized_id=Strike_R status=MATCH'
    $cardsOk = $cardsOk -and $cardOutputText -match 'uuid=10000000-0000-0000-0000-000000000002 dense_id=1 pile=hand pile_index=1 source_id=STRIKE_RED normalized_id=Strike_R status=MATCH'
    $cardsOk = $cardsOk -and $cardOutputText -match 'uuid=30000000-0000-0000-0000-000000000001 dense_id=4 pile=discard pile_index=0 source_id=RITUAL_DAGGER normalized_id=RitualDagger status=MATCH'
    $cardsOk = $cardsOk -and $cardOutputText -match 'piles.hand source_count=3 restored_count=3 status=MATCH'
    $cardsOk = $cardsOk -and $cardOutputText -match 'uuid=40000000-0000-0000-0000-000000000002 dense_id=6 pile=exhaust pile_index=1 source_id=SEARING_BLOW normalized_id=Searing Blow status=MATCH special_data_source=1 special_data_restored=1 upgrade_count_source=1 upgrade_count_restored=1'
    $cardsOk = $cardsOk -and $cardMatchLines.Count -eq 7
    if (-not $cardsOk) {
        throw "Java source-card to C++ restored-state diff failed: $cardOutputText"
    }
    $cardOutputText | Set-Content -LiteralPath (Join-Path $root 'build\live-state-validation\card-instance-interop.txt') -Encoding UTF8
    $cardDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $cardInteropFile -EngineOutputFile (Join-Path $root 'build\live-state-validation\card-instance-interop.txt') 2>&1
    if ($LASTEXITCODE -ne 0 -or ($cardDiff -join "`n") -notmatch 'MISMATCH=0 UNAVAILABLE=0') {
        throw "full card-instance fixture diff failed: $($cardDiff -join ' | ')"
    }
    Write-Output 'JAVA_CPP_CARD_INSTANCE_INTEROP evidence=OFFLINE_SOURCE_FIELDS_NOT_REAL_GAME_STATE instances=7 duplicate_card_id_distinct_uuid=PASS pile_order=PASS upgrades=PASS special_data=PASS'
} finally {
    Remove-Item -LiteralPath $cardInteropFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($cardInteropFile + '.capture.json') -Force -ErrorAction SilentlyContinue
}
$shrugSourceFixture = Join-Path $PSScriptRoot 'fixtures\live_battle_shrug_v1_source.json'
$shrugInteropFile = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-shrug-v1-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $shrugProduced = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $shrugInteropFile $shrugSourceFixture 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java Shrug It Off state serialization failed: $($shrugProduced -join ' | ')" }
    $shrugOutput = & $Engine mcts_state $shrugInteropFile 2>&1
    $shrugText = $shrugOutput -join "`n"
    $shrugCardLines = @([regex]::Matches($shrugText, '(?m)^STATE0_DETAIL_CARD .* normalized_id=Shrug It Off status=MATCH(?:\s|$)'))
    $shrugOk = $LASTEXITCODE -eq 0 -and $shrugCardLines.Count -eq 3 -and $shrugText -notmatch 'status=MISMATCH|status=UNAVAILABLE'
    foreach ($expect in @(
        'uuid=61000000-0000-0000-0000-000000000001 dense_id=0 pile=hand pile_index=0 source_id=SHRUG_IT_OFF normalized_id=Shrug It Off status=MATCH.*special_data_source=0 special_data_restored=0.*upgrade_count_source=0 upgrade_count_restored=0.*cost_source=1 cost_restored=1.*cost_for_turn_source=1 cost_for_turn_restored=1',
        'uuid=61000000-0000-0000-0000-000000000002 dense_id=1 pile=hand pile_index=1 source_id=SHRUG_IT_OFF normalized_id=Shrug It Off status=MATCH.*special_data_source=0 special_data_restored=0.*upgrade_count_source=1 upgrade_count_restored=1.*cost_source=1 cost_restored=1.*cost_for_turn_source=1 cost_for_turn_restored=1',
        'uuid=62000000-0000-0000-0000-000000000001 dense_id=2 pile=draw pile_index=0 source_id=SHRUG_IT_OFF normalized_id=Shrug It Off status=MATCH'
    )) { $shrugOk = $shrugOk -and $shrugText -match $expect }
    if (-not $shrugOk) { throw "Shrug Java→C++ identity/state readback failed: $shrugText" }
    $shrugDetail = Join-Path $root 'build\live-state-validation\shrug-instance-interop.txt'
    $shrugText | Set-Content -LiteralPath $shrugDetail -Encoding UTF8
    $shrugDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $shrugInteropFile -EngineOutputFile $shrugDetail 2>&1
    $shrugDiffText = $shrugDiff -join "`n"
    if ($LASTEXITCODE -ne 0 -or $shrugDiffText -match 'MISMATCH=[1-9]\d*|UNAVAILABLE=[1-9]\d*') {
        throw "Shrug full protocol field diff failed: $shrugDiffText"
    }

    $shrugCppBad = Get-Content -Raw -LiteralPath $shrugInteropFile | ConvertFrom-Json
    $shrugCppBad.piles.hand[0].special_data = 1
    [System.IO.File]::WriteAllText($shrugInteropFile, ($shrugCppBad | ConvertTo-Json -Depth 30), (New-Object System.Text.UTF8Encoding($false)))
    $previousPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $shrugRejected = & $Engine mcts_state $shrugInteropFile 2>&1
        $shrugRejectedExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $shrugRejectedText = $shrugRejected -join "`n"
    if ($shrugRejectedExit -ne 3 -or $shrugRejectedText -notmatch 'unsupported card special_data: SHRUG_IT_OFF') {
        throw "C++ Shrug special_data rejection assertion failed: exit=$shrugRejectedExit expected_exit=3 output=$shrugRejectedText"
    }
    Write-Output ("JAVA_CPP_SHRUG_INTEROP evidence=OFFLINE_SOURCE_FIELDS_NOT_REAL_GAME_STATE instances=3 normal=PASS upgraded=PASS duplicate_id_distinct_uuid=PASS pile_order=PASS cost_flags_special_data=PASS full_diff=PASS invalid_special_data=REJECTED; " + $shrugDiffText)
} finally {
    Remove-Item -LiteralPath $shrugInteropFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($shrugInteropFile + '.capture.json') -Force -ErrorAction SilentlyContinue
}
$havocSourceFixture = Join-Path $PSScriptRoot 'fixtures\live_battle_havoc_v1_source.json'
$havocInteropFile = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-havoc-v1-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $havocProduced = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $havocInteropFile $havocSourceFixture 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java Havoc state serialization failed: $($havocProduced -join ' | ')" }
    $havocOutput = & $Engine mcts_state $havocInteropFile 2>&1
    $havocText = $havocOutput -join "`n"
    $havocCardLines = @([regex]::Matches($havocText, '(?m)^STATE0_DETAIL_CARD .* normalized_id=Havoc status=MATCH(?:\s|$)'))
    $havocOk = $LASTEXITCODE -eq 0 -and $havocCardLines.Count -eq 5 -and $havocText -notmatch 'status=MISMATCH|status=UNAVAILABLE'
    foreach ($expect in @(
        'uuid=51000000-0000-0000-0000-000000000001 dense_id=0 pile=hand pile_index=0 source_id=HAVOC normalized_id=Havoc status=MATCH.*upgrade_count_source=0 upgrade_count_restored=0',
        'uuid=51000000-0000-0000-0000-000000000002 dense_id=1 pile=hand pile_index=1 source_id=HAVOC normalized_id=Havoc status=MATCH.*upgrade_count_source=1 upgrade_count_restored=1',
        'uuid=52000000-0000-0000-0000-000000000001 dense_id=2 pile=draw pile_index=0 source_id=HAVOC normalized_id=Havoc status=MATCH',
        'uuid=53000000-0000-0000-0000-000000000001 dense_id=3 pile=discard pile_index=0 source_id=HAVOC normalized_id=Havoc status=MATCH',
        'uuid=54000000-0000-0000-0000-000000000001 dense_id=4 pile=exhaust pile_index=0 source_id=HAVOC normalized_id=Havoc status=MATCH'
    )) { $havocOk = $havocOk -and $havocText -match $expect }
    if (-not $havocOk) { throw "Havoc pile/identity/upgrade roundtrip failed: $havocText" }
    $havocDetail = Join-Path $root 'build\live-state-validation\havoc-instance-interop.txt'
    $havocText | Set-Content -LiteralPath $havocDetail -Encoding UTF8
    $havocDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $havocInteropFile -EngineOutputFile $havocDetail 2>&1
    if ($LASTEXITCODE -ne 0 -or ($havocDiff -join "`n") -notmatch 'MISMATCH=0 UNAVAILABLE=0') {
        throw "full Havoc instance fixture diff failed: $($havocDiff -join ' | ')"
    }

    $unsafe = Get-Content -Raw -LiteralPath $havocInteropFile | ConvertFrom-Json
    $unsafe.piles.hand[0].id = 'RAMPAGE'
    $unsafeFile = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-unmodeled-card-' + [guid]::NewGuid().ToString('N') + '.json')
    [System.IO.File]::WriteAllText($unsafeFile, ($unsafe | ConvertTo-Json -Depth 30), (New-Object System.Text.UTF8Encoding($false)))
    try {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $unsafeOutput = & $Engine mcts_state_search $unsafeFile 16 2>&1
        $unsafeExit = $LASTEXITCODE
        $ErrorActionPreference = $previousPreference
        if ($unsafeExit -eq 0 -or ($unsafeOutput -join "`n") -notmatch 'unsupported card outside verified mapping slice: RAMPAGE') {
            throw "unmodeled card reached combat search instead of fail-closed parser rejection: $($unsafeOutput -join ' | ')"
        }
    } finally { $ErrorActionPreference = 'Stop'; Remove-Item -LiteralPath $unsafeFile -Force -ErrorAction SilentlyContinue }

    $diagnosticInput = Get-Content -Raw -LiteralPath $havocSourceFixture | ConvertFrom-Json
    $diagnosticInput.draw[0].card_id = 'Rampage'
    $diagnosticInput.discard[0].upgrades = 2
    $diagIn = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-card-diagnostic-input-' + [guid]::NewGuid().ToString('N') + '.json')
    $diagOut = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-card-diagnostic-output-' + [guid]::NewGuid().ToString('N') + '.json')
    [System.IO.File]::WriteAllText($diagIn, ($diagnosticInput | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
    try {
        $diagRun = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer --diagnose-cards $diagIn $diagOut 2>&1
        if ($LASTEXITCODE -ne 0) { throw "card mapping diagnostic failed: $($diagRun -join ' | ')" }
        $diag = Get-Content -Raw -LiteralPath $diagOut | ConvertFrom-Json
        $diagCount = 0
        foreach ($pile in @('hand','draw','discard','exhaust')) { $diagCount += $diag.piles.$pile.Count }
        if ($diag.evidence -ne 'OFFLINE_FIXTURE_NOT_REAL_GAME_STATE' -or $diagCount -ne 5 -or
                $diag.piles.hand[0].mapping_diagnostic.classification -ne 'CAPTURABLE_AND_CXX_EFFECT_PRESENT' -or
                $diag.piles.draw[0].mapping_diagnostic.classification -ne 'UNSUPPORTED_IDENTITY' -or
                $diag.piles.discard[0].mapping_diagnostic.classification -ne 'IDENTITY_KNOWN_STATE_UNMAPPED' -or
                $diag.piles.hand[0].mapping_diagnostic.engine_card_id -ne 'HAVOC') {
            throw "diagnostic did not enumerate/classify all card mapping outcomes: $($diagRun -join ' | ')"
        }
        $diagProtocolOut = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-card-diagnostic-protocol-' + [guid]::NewGuid().ToString('N') + '.json')
        try {
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $diagProtocol = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $diagProtocolOut $diagIn 2>&1
            $diagProtocolExit = $LASTEXITCODE
            $ErrorActionPreference = $previousPreference
            if ($diagProtocolExit -eq 0 -or (Test-Path -LiteralPath $diagProtocolOut)) {
                throw 'diagnostic-only unsupported card input was published as a protocol snapshot'
            }
        } finally { $ErrorActionPreference = 'Stop'; Remove-Item -LiteralPath $diagProtocolOut -Force -ErrorAction SilentlyContinue }
    } finally {
        Remove-Item -LiteralPath $diagIn -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $diagOut -Force -ErrorAction SilentlyContinue
    }
    Write-Output 'JAVA_CPP_HAVOC_INTEROP evidence=OFFLINE_SOURCE_FIELDS_NOT_REAL_GAME_STATE instances=5 piles=4 same_id_distinct_uuid=PASS upgrade_0_1=PASS effect_gate=PASS diagnostics_all_cards=PASS protocol_fail_closed=PASS'
} finally {
    Remove-Item -LiteralPath $havocInteropFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($havocInteropFile + '.capture.json') -Force -ErrorAction SilentlyContinue
}
$sentrySourceFixture = Join-Path $PSScriptRoot 'fixtures\live_battle_sentries_v1_source.json'
$sentryInteropFile = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-sentry-v1-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $sentryProduced = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $sentryInteropFile $cardSourceFixture $sentrySourceFixture 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java source-Sentry serialization failed: $($sentryProduced -join ' | ')" }
    $sentryOutput = & $Engine mcts_state $sentryInteropFile 2>&1
    $sentryText = $sentryOutput -join "`n"
    $sentryMatchFields = @([regex]::Matches($sentryText, '(?m)^STATE0_DETAIL_FIELD path=monsters\[\d+\]\.(?:id|slot|hp|max_hp|block|escaping|half_dead|escape_next|beam_damage|dazed_amount|first_move|misc_info|unique_power0|unique_power1|move_history\[\d+\]) .* status=MATCH$'))
    $sentryStatuses = @([regex]::Matches($sentryText, '(?m)^STATE0_DETAIL_FIELD path=monsters\[\d+\]\.statuses .* status=MATCH$'))
    $sentryOk = $LASTEXITCODE -eq 0 -and $sentryText -notmatch 'status=MISMATCH|status=UNAVAILABLE'
    $sentryOk = $sentryOk -and $sentryMatchFields.Count -eq 48 -and $sentryStatuses.Count -eq 3
    foreach ($expect in @(
        'path=monsters\[0\]\.move_history\[0\] source="SENTRY_BOLT" restored="SENTRY_BOLT" status=MATCH',
        'path=monsters\[0\]\.move_history\[1\] source="INVALID" restored="INVALID" status=MATCH',
        'path=monsters\[1\]\.move_history\[0\] source="SENTRY_BOLT" restored="SENTRY_BOLT" status=MATCH',
        'path=monsters\[1\]\.move_history\[1\] source="SENTRY_BEAM" restored="SENTRY_BEAM" status=MATCH',
        'path=monsters\[2\]\.move_history\[0\] source="SENTRY_BEAM" restored="SENTRY_BEAM" status=MATCH',
        'path=monsters\[2\]\.statuses source=\[\{"amount":1,"id":"ARTIFACT","just_applied":false\},\{"amount":1,"id":"VULNERABLE","just_applied":false\}\] restored=\[\{"amount":1,"id":"ARTIFACT","just_applied":false\},\{"amount":1,"id":"VULNERABLE","just_applied":false\}\] status=MATCH'
    )) { $sentryOk = $sentryOk -and $sentryText -match $expect }
    if (-not $sentryOk) { throw "Java source-Sentry to C++ restored-state diff failed: $sentryText" }
    $sentryText | Set-Content -LiteralPath (Join-Path $root 'build\live-state-validation\sentry-instance-interop.txt') -Encoding UTF8
    $sentryDiff = & (Join-Path $PSScriptRoot 'live-state-v1-full-diff.ps1') -StateFile $sentryInteropFile -EngineOutputFile (Join-Path $root 'build\live-state-validation\sentry-instance-interop.txt') 2>&1
    if ($LASTEXITCODE -ne 0 -or ($sentryDiff -join "`n") -notmatch 'paths=149 MATCH=148 MISMATCH=0 UNAVAILABLE=0 NOT_APPLICABLE=1') {
        throw "full Sentry fixture diff failed: $($sentryDiff -join ' | ')"
    }
    Copy-Item -LiteralPath (Join-Path $root 'build\live-state-validation\live-state-v1-full-field-diff.txt') -Destination (Join-Path $root 'build\live-state-validation\sentry-field-diff.txt') -Force
    Write-Output "JAVA_CPP_SENTRY_INTEROP evidence=OFFLINE_SOURCE_FIELDS_NOT_REAL_GAME_STATE monsters=3 restored_fields=$($sentryMatchFields.Count) status_sets=$($sentryStatuses.Count) path_shapes=149 path_matches=148 move_history_order=PASS artifact=PASS weak_timing=PASS vulnerable_timing=PASS report=build/live-state-validation/sentry-field-diff.txt"
} finally {
    Remove-Item -LiteralPath $sentryInteropFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($sentryInteropFile + '.capture.json') -Force -ErrorAction SilentlyContinue
}
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('cs1-live-state-v1-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    function Assert-JavaWriterRejects([string]$case, [scriptblock]$mutate, [string]$reason) {
        $json = Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json
        & $mutate $json
        [System.IO.File]::WriteAllText($temp, ($json | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
        Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $output = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $temp $generated 2>&1 }
        finally { $ErrorActionPreference = $previous }
        if ($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath $generated) -or ($output -join "`n") -notmatch [regex]::Escape($reason)) {
            throw "$case was not rejected before protocol file creation: $($output -join ' | ')"
        }
    }
    function Assert-SourceCardRejects([string]$case, [scriptblock]$mutate, [string]$reason) {
        $base = Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json
        $source = Get-Content -Raw -LiteralPath $cardSourceFixture | ConvertFrom-Json
        & $mutate $source
        [System.IO.File]::WriteAllText($temp, ($source | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
        Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $output = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $generated $temp 2>&1 }
        finally { $ErrorActionPreference = $previous }
        if ($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath $generated) -or ($output -join "`n") -notmatch [regex]::Escape($reason)) {
            throw "$case was not rejected before protocol file creation: $($output -join ' | ')"
        }
    }
    function Assert-ShrugSourceRejects([string]$case, [scriptblock]$mutate, [string]$reason) {
        $source = Get-Content -Raw -LiteralPath $shrugSourceFixture | ConvertFrom-Json
        & $mutate $source
        [System.IO.File]::WriteAllText($temp, ($source | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
        Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
        $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { $output = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $generated $temp 2>&1 }
        finally { $ErrorActionPreference = $previous }
        if ($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath $generated) -or ($output -join "`n") -notmatch [regex]::Escape($reason)) {
            throw "$case was not rejected before protocol file creation: $($output -join ' | ')"
        }
    }
    function Assert-SourceSentryRejects([string]$case, [scriptblock]$mutate, [string]$reason) {
        $source = Get-Content -Raw -LiteralPath $sentrySourceFixture | ConvertFrom-Json
        & $mutate $source
        [System.IO.File]::WriteAllText($temp, ($source | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
        Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $output = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $fixture $generated $cardSourceFixture $temp 2>&1 }
        finally { $ErrorActionPreference = $previous }
        if ($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath $generated) -or ($output -join "`n") -notmatch [regex]::Escape($reason)) {
            throw "$case was not rejected before protocol file creation: $($output -join ' | ')"
        }
    }
    function Assert-Rejected([string]$case, [scriptblock]$mutate, [string]$reason) {
        $json = Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json
        & $mutate $json
        $json | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temp -Encoding UTF8
        # Windows PowerShell 5.1 promotes native stderr to a terminating error
        # when ErrorActionPreference is Stop. Rejection diagnostics are expected.
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $output = & $Engine mcts_state $temp 2>&1 }
        finally { $ErrorActionPreference = $previous }
        if ($LASTEXITCODE -ne 3 -or ($output -join "`n") -notmatch [regex]::Escape($reason)) {
            throw "$case was not safely rejected: $($output -join ' | ')"
        }
    }
    Assert-Rejected 'unstable stage' { param($j) $j.stage = 'EXECUTING_ACTIONS' } 'unstable or unsupported battle stage'
    Assert-Rejected 'queues_empty false' { param($j) $j.queues_empty = $false } 'action/card/monster queues are not empty'
    Assert-Rejected 'battle already over' { param($j) $j.battle_over = $true } 'battle is not at an idle player action boundary'
    Assert-Rejected 'end-turn already queued' { param($j) $j.end_turn_queued = $true } 'battle is not at an idle player action boundary'
    Assert-Rejected 'player turn ended' { param($j) $j.turn_has_ended = $true } 'battle is not at an idle player action boundary'
    Assert-Rejected 'monster turn skipped' { param($j) $j.skip_monster_turn = $true } 'battle is not at an idle player action boundary'
    Assert-Rejected 'action queue not empty' { param($j) $j.action_queue_size = 1 } 'action/card queue is not empty'
    Assert-Rejected 'card queue not empty' { param($j) $j.card_queue_size = 1 } 'action/card queue is not empty'
    Assert-Rejected 'negative player HP' { param($j) $j.player.hp = -1 } 'player HP is outside valid range'
    Assert-Rejected 'player HP exceeds max' { param($j) $j.player.hp = 81 } 'player HP is outside valid range'
    Assert-Rejected 'zero max player HP' { param($j) $j.player.max_hp = 0 } 'player HP is outside valid range'
    Assert-Rejected 'negative player block' { param($j) $j.player.block = -1 } 'player block/energy is negative'
    Assert-Rejected 'negative player energy' { param($j) $j.player.energy = -1 } 'player block/energy is negative'
    Assert-Rejected 'negative energy per turn' { param($j) $j.player.energy_per_turn = -1 } 'player block/energy is negative'
    Assert-Rejected 'missing player power timing flag' { param($j) $j.player.statuses[0].PSObject.Properties.Remove('just_applied') } 'missing required player status just_applied: DOUBLE_DAMAGE'
    Assert-Rejected 'non-boolean player power timing flag' { param($j) $j.player.statuses[0].just_applied = 'true' } 'player status just_applied must be boolean: DOUBLE_DAMAGE'
    Assert-Rejected 'inapplicable player timing flag' { param($j) $j.player.statuses[3] | Add-Member -NotePropertyName just_applied -NotePropertyValue $false } 'player status just_applied is not applicable: INTANGIBLE'
    Assert-Rejected 'unknown player status' { param($j) $j.player.statuses[0].id = 'UNKNOWN_POWER' } 'unknown/unsupported player status id: UNKNOWN_POWER'
    function Assert-Event-Rejected([string]$case, [scriptblock]$mutate, [string]$reason) {
        $json = Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json
        $json.room_context.kind = 'EVENT'; $json.room_context.class = 'com.megacrit.cardcrawl.rooms.EventRoom'
        $json.room_context.event_class = 'com.megacrit.cardcrawl.events.exordium.DeadAdventurer'
        $json.room_context.event_encounter = '3 Sentries'; $json.room_context.event_enemy_index = 0
        & $mutate $json
        $json | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temp -Encoding UTF8
        $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { $output = & $Engine mcts_state $temp 2>&1 } finally { $ErrorActionPreference = $previous }
        if ($LASTEXITCODE -ne 3 -or ($output -join "`n") -notmatch [regex]::Escape($reason)) {
            throw "$case was not safely rejected: $($output -join ' | ')"
        }
    }
    Assert-Event-Rejected 'wrong event class' { param($j) $j.room_context.event_class = 'com.megacrit.cardcrawl.events.Event' } 'unsupported event provenance'
    Assert-Event-Rejected 'wrong event enemy index' { param($j) $j.room_context.event_enemy_index = 1 } 'unsupported event provenance'
    Assert-Event-Rejected 'wrong event encounter name' { param($j) $j.room_context.event_encounter = 'Gremlin Nob' } 'unsupported event provenance'
    Assert-Event-Rejected 'wrong engine encounter id' { param($j) $j.encounter = 'TWO_THIEVES' } 'unsupported encounter context; non-three-Sentry state including stolen-gold bookkeeping is unavailable'
    Assert-Event-Rejected 'event with non-EventRoom runtime class' { param($j) $j.room_context.class = 'com.megacrit.cardcrawl.rooms.MonsterRoomElite' } 'unsupported event provenance'
    Assert-Event-Rejected 'event without elite trigger' { param($j) $j.room_context.elite_trigger = $false } 'unsupported event provenance'
    Assert-Event-Rejected 'event roster mismatch' { param($j) $j.monsters[1].id = 'GREMLIN_NOB' } 'only verified initialized Sentry state is supported'
    Assert-Rejected 'theft-capable encounter' { param($j) $j.encounter = 'TWO_THIEVES' } 'non-three-Sentry state including stolen-gold bookkeeping'
    Assert-Rejected 'missing room provenance' { param($j) $j.PSObject.Properties.Remove('room_context') } 'missing field: room_context'
    Assert-Rejected 'obsolete internal counter input' { param($j) $j | Add-Member -NotePropertyName battle_counters -NotePropertyValue @{loop_count=0;energy_wasted=0;cards_drawn=0;misc_bits=@()} } 'battle_counters is obsolete'
    Assert-Rejected 'duplicate card identity' { param($j) $j.piles.discard = @($j.piles.hand[0]) } 'invalid or duplicate card UUID'
    Assert-Rejected 'unknown engine card id' { param($j) $j.piles.hand[0].id = 'NOT_A_CARD' } 'unknown card id'
    Assert-Rejected 'illegal engine upgrade state' { param($j) $j.piles.hand[0].upgrade_count = 2 } 'unsupported card upgrade count/state'
    Assert-Rejected 'unknown protocol version' { param($j) $j.version = 99 } 'unsupported live_battle_state version'
    Assert-Rejected 'invalid capture transaction id' { param($j) $j.capture_id = 'fixture-run-room-7' } 'capture_id must be a UUID transaction identifier'
    Assert-Rejected 'missing required player counter' { param($j) $j.player.counters.PSObject.Properties.Remove('panache') } 'missing field: panache'
    Assert-Rejected 'nonempty limbo at player boundary' { param($j) $j.piles.limbo = @($j.piles.hand[0]) } 'limbo/stasis cards are unsupported'
    Assert-Rejected 'nonempty stasis in three-Sentry state' { param($j) $j.piles.stasis = @($j.piles.hand[0]) } 'limbo/stasis cards are unsupported'
    Assert-Rejected 'card counter contradicts restored piles' { param($j) $j.card_counters.hand_normality = 1 } 'card_counters disagree with exact pile-derived values'
    Assert-Rejected 'negative RNG counter' { param($j) $j.rng.ai.counter = -1 } 'negative RNG counter'
    Assert-Rejected 'unsupported Sentry power' { param($j) $j.monsters[0].statuses = @(@{id='POISON';amount=2;just_applied=$false}) } 'unsupported Sentry status id'
    Assert-Rejected 'unknown C++ Sentry move name' { param($j) $j.monsters[0].move_history[0] = 'SENTRY_UNKNOWN' } 'unknown monster move'
    Assert-Rejected 'duplicate Sentry status' { param($j) $j.monsters[0].statuses = @(@{id='WEAK';amount=1;just_applied=$false},@{id='WEAK';amount=2;just_applied=$true}) } 'unknown or duplicate monster status id'
    Assert-Rejected 'nonzero Sentry unique-power slot' { param($j) $j.monsters[0].unique_power0 = 1 } 'unsupported nonzero Sentry-only internal counter'
    Assert-Rejected 'nonzero Sentry misc counter' { param($j) $j.monsters[0].misc_info = 1 } 'unsupported nonzero Sentry-only internal counter'
    Assert-Rejected 'unsupported card special data' { param($j) $j.piles.hand[0].special_data = 1 } 'unsupported card special_data'
    Assert-Rejected 'Havoc cannot carry nonzero C++ special data' { param($j) $j.piles.hand[0].id = 'HAVOC'; $j.piles.hand[0].special_data = 1 } 'unsupported card special_data: HAVOC'
    Assert-Rejected 'outside card mapping slice' { param($j) $j.piles.hand[0].id = 'STRIKE_BLUE' } 'unsupported card outside verified mapping slice'
    Assert-Rejected 'unmapped Rampage engine instance' { param($j) $j.piles.hand[0].id = 'RAMPAGE' } 'unsupported card outside verified mapping slice'
    Assert-Rejected 'unsupported room class' { param($j) $j.room_context.class = 'com.megacrit.cardcrawl.rooms.EventRoom' } 'unsupported map room context'
    Assert-JavaWriterRejects 'Java writer missing required counter' { param($j) $j.player.counters.PSObject.Properties.Remove('panache') } 'missing required protocol field: panache'
    Assert-JavaWriterRejects 'Java writer duplicate card UUID' { param($j) $j.piles.discard = @($j.piles.hand[0]) } 'invalid or duplicate full card UUID'
    Assert-JavaWriterRejects 'Java writer malformed UUID' { param($j) $j.piles.hand[0].uuid = 'not-a-full-uuid' } 'invalid or duplicate full card UUID'
    Assert-JavaWriterRejects 'Java writer unsupported version' { param($j) $j.version = 2 } 'unexpected version'
    Assert-JavaWriterRejects 'Java writer rejects wrong event index' { param($j)
        $j.room_context.kind = 'EVENT'; $j.room_context.class = 'com.megacrit.cardcrawl.rooms.EventRoom'
        $j.room_context.event_class = 'com.megacrit.cardcrawl.events.exordium.DeadAdventurer'
        $j.room_context.event_encounter = '3 Sentries'; $j.room_context.event_enemy_index = 1
    } 'unsupported event provenance'
    Assert-JavaWriterRejects 'Java writer rejects non-Dead-Adventurer event' { param($j)
        $j.room_context.kind = 'EVENT'; $j.room_context.class = 'com.megacrit.cardcrawl.rooms.EventRoom'
        $j.room_context.event_class = 'com.megacrit.cardcrawl.events.exordium.TheCleric'
        $j.room_context.event_encounter = '3 Sentries'; $j.room_context.event_enemy_index = 0
    } 'unsupported event provenance'
    Assert-JavaWriterRejects 'Java writer rejects wrong event encounter' { param($j)
        $j.room_context.kind = 'EVENT'; $j.room_context.class = 'com.megacrit.cardcrawl.rooms.EventRoom'
        $j.room_context.event_class = 'com.megacrit.cardcrawl.events.exordium.DeadAdventurer'
        $j.room_context.event_encounter = 'Gremlin Nob'; $j.room_context.event_enemy_index = 0
    } 'unsupported event provenance'
    Assert-JavaWriterRejects 'Java writer inconsistent upgrades' { param($j) $j.piles.hand[0].upgrade_count = 2 } 'unsupported card upgrade count/state'
    Assert-JavaWriterRejects 'Java writer unknown engine card' { param($j) $j.piles.hand[0].id = 'NOT_A_CARD' } 'unsupported engine card id'
    Assert-JavaWriterRejects 'Java writer missing timing source' { param($j) $j.player.statuses[0].PSObject.Properties.Remove('just_applied') } 'missing required player status just_applied: DOUBLE_DAMAGE'
    Assert-JavaWriterRejects 'Java writer timing on irrelevant status' { param($j) $j.player.statuses[3] | Add-Member -NotePropertyName just_applied -NotePropertyValue $false } 'player status just_applied is not applicable: INTANGIBLE'
    Assert-SourceCardRejects 'unknown native card ID' { param($j) $j.hand[0].card_id = 'Not A Card' } 'unsupported vanilla Ironclad card id'
    Assert-SourceCardRejects 'unmapped Rampage progression state' { param($j) $j.hand[0].card_id = 'Rampage' } 'unsupported vanilla Ironclad card id'
    Assert-SourceCardRejects 'illegal source upgrade count' { param($j) $j.hand[0].upgrades = 2 } 'unsupported card upgrade count/state'
    Assert-SourceCardRejects 'unsupported native misc state' { param($j) $j.hand[0].misc = 1 } 'unsupported nonzero AbstractCard.misc'
    Assert-SourceCardRejects 'unsupported intrinsic ethereal state' { param($j) $j.hand[0].ethereal = $true } 'unsupported ethereal state for canonical card mapping'
    Assert-SourceCardRejects 'missing ethereal source field' { param($j) $j.hand[0].PSObject.Properties.Remove('ethereal') } 'missing or invalid card field: ethereal'
    Assert-SourceCardRejects 'duplicate source UUID' { param($j) $j.hand[1].uuid = $j.hand[0].uuid } 'duplicate full card UUID'
    Assert-ShrugSourceRejects 'Shrug missing cost field' { param($j) $j.hand[0].PSObject.Properties.Remove('cost') } 'missing or invalid card field: cost'
    Assert-ShrugSourceRejects 'Shrug illegal upgrade count' { param($j) $j.hand[0].upgrades = 2 } 'unsupported card upgrade count/state: Shrug It Off upgrades=2'
    Assert-ShrugSourceRejects 'Shrug unsupported nonzero misc' { param($j) $j.hand[0].misc = 1 } 'unsupported nonzero AbstractCard.misc for Shrug It Off'
    Assert-ShrugSourceRejects 'Shrug unsupported ethereal state' { param($j) $j.hand[0].ethereal = $true } 'unsupported ethereal state for canonical card mapping: Shrug It Off'
    Assert-ShrugSourceRejects 'Shrug duplicate instance UUID' { param($j) $j.hand[1].uuid = $j.hand[0].uuid } 'duplicate full card UUID'
    Assert-SourceSentryRejects 'unknown native Sentry move id' { param($j) $j.monsters[1].move_history[1] = 99 } 'unsupported native Sentry move id'
    Assert-SourceSentryRejects 'Sentry slot mismatch' { param($j) $j.monsters[1].slot = 0 } 'Sentry slot does not preserve original MonsterGroup order'
    Assert-SourceSentryRejects 'unknown Sentry power class' { param($j) $j.monsters[0].powers[0].class_name = 'com.example.UnknownPower' } 'unsupported Sentry power class/ID'
    Assert-SourceSentryRejects 'missing Sentry debuff timing' { param($j) $j.monsters[1].powers[1].PSObject.Properties.Remove('just_applied') } 'missing/invalid Sentry power justApplied'
    Assert-SourceSentryRejects 'Sentry firstMove true' { param($j) $j.monsters[0].firstMove = $true } 'unsupported Sentry firstMove=true'
    $autoIdInput = Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json
    $autoIdInput.PSObject.Properties.Remove('capture_id')
    [System.IO.File]::WriteAllText($temp, ($autoIdInput | ConvertTo-Json -Depth 20), (New-Object System.Text.UTF8Encoding($false)))
    $autoIdOutput = & $java -cp ($classes + ';' + $gameJar) rngprobe.LiveBattleStateV1OfflineProducer $temp $generated 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java writer did not generate transport capture_id: $($autoIdOutput -join ' | ')" }
    $autoSnapshot = Get-Content -Raw -LiteralPath $generated | ConvertFrom-Json
    $autoManifest = Get-Content -Raw -LiteralPath ($generated + '.capture.json') | ConvertFrom-Json
    $autoHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $generated).Hash.ToLowerInvariant()
    if ($autoSnapshot.capture_id -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' -or
            $autoManifest.capture_id -ne $autoSnapshot.capture_id -or $autoManifest.snapshot_sha256 -ne $autoHash) {
        throw 'Java writer capture_id was not independently generated and bound to the snapshot SHA-256'
    }
} finally {
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $generated -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath ($generated + '.capture.json') -Force -ErrorAction SilentlyContinue
}
Write-Output 'LIVE_STATE_V1_CHECK_OK valid_root=1 player_basic_readback=7_changed_fields boundary_readback=PASS player_range_rejects=6 boundary_rejects=7 missing_status_timing_rejected=PASS act_roundtrip=2 capture_manifest_hash=PASS card_identity_match=4 source_card_interop=7 shrug_instances=3 shrug_normal_upgraded=PASS shrug_distinct_uuid=PASS shrug_full_diff=PASS shrug_source_rejects=5 shrug_invalid_special_data_rejected=PASS havoc_instances=5 havoc_all_piles=PASS havoc_upgrade_0_1=PASS same_id_distinct_uuid=PASS unmodeled_effect_fail_closed=PASS all_card_diagnostics=PASS pile_order=PASS upgrades=PASS special_data=PASS source_rejects=7 sentry_source_interop=3 sentry_readback_fields=48 sentry_status_sets=3 sentry_source_rejects=5 sentry_cpp_rejects=5 nonempty_piles=PASS status_relic_potion=PASS derived_card_counters=PASS cursor_order=0,1,2'
exit 0
