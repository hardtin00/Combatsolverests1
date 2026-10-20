param(
    [string]$Fixture = '',
    [string]$Output = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Fixture) { $Fixture = Join-Path $root 'tools\solvertest\fixtures\live_battle_state_v1_sentry.json' }
if (-not $Output) { $Output = Join-Path $root 'docs\LIVE_BATTLE_STATE_V1_FIELD_AUDIT.md' }
$state = Get-Content -Raw -LiteralPath $Fixture | ConvertFrom-Json
$fields = [System.Collections.Generic.Dictionary[string,string]]::new()
function Visit($value, [string]$path) {
    if ($value -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $value.PSObject.Properties) {
            if ($path -eq '$' -and $property.Name -eq 'test_only') { continue }
            Visit $property.Value ($path + '.' + $property.Name)
        }
        return
    }
    if ($value -is [array]) {
        if ($value.Count -eq 0) { $fields[$path + '[]'] = 'array item shape defined by protocol/C++ parser; fixture array is empty' }
        foreach ($item in $value) { Visit $item ($path + '[]') }
        return
    }
    if ($null -eq $value) { $fields[$path] = 'null (explicit)' }
    elseif ($value -is [bool]) { $fields[$path] = 'boolean' }
    elseif ($value -is [string]) { $fields[$path] = 'string' }
    elseif ($value -is [ValueType]) { $fields[$path] = 'integer' }
    else { $fields[$path] = 'unknown' }
}
Visit $state '$'
# Empty arrays in the compact fixture still have required item schemas.
foreach ($pile in @('hand','draw','discard','exhaust')) {
    foreach ($field in @('uuid','id','special_data','cost','cost_for_turn','upgraded','upgrade_count','free_to_play_once','retain')) {
        $type = if ($field -in @('uuid','id')) { 'string' } elseif ($field -in @('upgraded','free_to_play_once','retain')) { 'boolean' } else { 'integer' }
        $fields[('$.piles.{0}[].{1}' -f $pile,$field)] = $type
    }
}
foreach ($prefix in @('$.player.statuses[]','$.monsters[].statuses[]')) {
    $fields[$prefix + '.id'] = 'string'
    $fields[$prefix + '.amount'] = 'integer'
    $fields[$prefix + '.just_applied'] = 'boolean'
}
$fields['$.relic_counters[].id'] = 'string'
$fields['$.relic_counters[].value'] = 'integer'

$unavailable = @(
    '$.card_counters.discard_blood', '$.card_counters.draw_blood', '$.card_counters.hand_blood',
    '$.card_counters.hand_normality', '$.card_counters.hand_pain', '$.card_counters.strikes_in_combat',
    '$.monster_group.extra_roll[]', '$.monster_group.skip_turn[]',
    '$.monsters[].misc_info', '$.monsters[].statuses[]', '$.monsters[].statuses[].just_applied', '$.monsters[].unique_power0', '$.monsters[].unique_power1',
    '$.player.counters.attacks_played', '$.player.counters.bomb1', '$.player.counters.bomb2', '$.player.counters.bomb3',
    '$.player.counters.cards_discarded', '$.player.counters.cards_played', '$.player.counters.combust_hp_loss',
    '$.player.counters.deva_energy', '$.player.counters.echo_doubled', '$.player.counters.last_attack_unblocked',
    '$.player.counters.necronomicon_used', '$.player.counters.orange_pellets[]', '$.player.counters.panache',
    '$.player.counters.skills_played', '$.player.counters.times_damaged', '$.player.statuses[]', '$.player.statuses[].just_applied', '$.relic_counters[]', '$.relic_counters[].id', '$.relic_counters[].value',
    '$.skip_monster_turn', '$.battle_over', '$.end_turn_queued', '$.turn_has_ended',
    '$.player.orb_slots', '$.player.last_targeted_monster', '$.player.strength', '$.player.dexterity', '$.player.focus', '$.player.artifact',
    '$.player.energy_per_turn', '$.player.stance', '$.monsters[].move_history[]',
    '$.piles.hand[].special_data', '$.piles.draw[].special_data', '$.piles.discard[].special_data', '$.piles.exhaust[].special_data',
    '$.piles.hand[].id', '$.piles.draw[].id', '$.piles.discard[].id', '$.piles.exhaust[].id'
)
$verified = @(
    '$.act', '$.ascension', '$.character', '$.floor', '$.seed', '$.turn',
    '$.room_context.class', '$.room_context.elite_trigger', '$.room_context.event_class', '$.room_context.event_encounter',
    '$.room_context.event_enemy_index', '$.room_context.kind', '$.rng.ai.counter', '$.rng.ai.seed0', '$.rng.ai.seed1', '$.rng.ai.stream_id',
    '$.rng.card.counter', '$.rng.card.seed0', '$.rng.card.seed1', '$.rng.card.stream_id', '$.rng.misc.counter', '$.rng.misc.seed0',
    '$.rng.misc.seed1', '$.rng.misc.stream_id', '$.rng.monster_hp.counter', '$.rng.monster_hp.seed0', '$.rng.monster_hp.seed1',
    '$.rng.monster_hp.stream_id', '$.rng.potion.counter', '$.rng.potion.seed0', '$.rng.potion.seed1', '$.rng.potion.stream_id',
    '$.rng.shuffle.counter', '$.rng.shuffle.seed0', '$.rng.shuffle.seed1', '$.rng.shuffle.stream_id',
    '$.player.hp', '$.player.max_hp', '$.player.block', '$.player.energy', '$.player.energy_per_turn', '$.player.card_draw_per_turn',
    '$.player.gold', '$.player.stance', '$.piles.hand[].uuid', '$.piles.hand[].id', '$.piles.hand[].cost',
    '$.piles.hand[].cost_for_turn', '$.piles.hand[].upgrade_count', '$.piles.hand[].upgraded',
    '$.piles.hand[].free_to_play_once', '$.piles.hand[].retain', '$.relics[].id', '$.relic_counters[]', '$.potions[].id',
    '$.monsters[].id', '$.monsters[].hp', '$.monsters[].max_hp', '$.monsters[].block', '$.monsters[].move_history[]',
    '$.monsters[].first_move', '$.monsters[].beam_damage', '$.monsters[].dazed_amount',
    '$.queues_empty', '$.action_queue_size', '$.card_queue_size', '$.stage'
)
$unverified = @()

foreach ($pile in @('hand','draw','discard','exhaust')) {
    foreach ($field in @('uuid','cost','cost_for_turn','upgrade_count','upgraded','free_to_play_once','retain')) {
        $candidate = '$.piles.' + $pile + '[].' + $field
        if ($verified -notcontains $candidate) { $verified += $candidate }
    }
}

$rows = [System.Collections.Generic.List[string]]::new()
$fixtureDiffPath = Join-Path $root 'build\live-state-validation\live-state-v1-full-field-diff.txt'
$fixtureStatuses = @{}
if (Test-Path -LiteralPath $fixtureDiffPath) {
    foreach ($diffLine in Get-Content -LiteralPath $fixtureDiffPath) {
        if ($diffLine -match '^\| `(\$\.[^`]+)` \| \w+ \| \*\*(MATCH|MISMATCH|UNAVAILABLE|NOT_APPLICABLE)\*\*') { $fixtureStatuses[$Matches[1]] = $Matches[2] }
    }
}
$rows.Add('| Protocol path | Type | Classification | Classification rationale | STS1 source / conversion | C++ destination | Evidence / mapping status | Offline fixture comparison |')
$rows.Add('|---|---|---|---|---|---|---|---|---|')
$countVerified = 0; $countUnverified = 0; $countUnavailable = 0
$classificationCounts = @{ REQUIRED = 0; CONDITIONAL = 0; DERIVABLE = 0; INTERNAL = 0; UNUSED = 0; BLOCKED = 0 }
foreach ($path in ($fields.Keys | Sort-Object)) {
    $type = $fields[$path]
    $source = 'See source group below; exact member or semantic conversion not confirmed.'
    $cpp = 'See parser field path; no full detail comparison.'
    $evidence = 'fixture/schema only'
    $status = 'UNVERIFIED'
    if ($unavailable -contains $path) {
        $status = 'UNAVAILABLE'
        $evidence = 'No verified probe mapping; some paths are absent from collector.'
    } elseif ($verified -contains $path) {
        $status = 'VERIFIED'
        $evidence = 'Probe reflection/capture source and installed desktop-1.0.jar bytecode audit; RNG also has prior real no-advance evidence.'
    }
    switch -Regex ($path) {
        '^\$\.act$' { $source='AbstractDungeon.actNum static int; copied as an integer without conversion.'; $cpp='BattleContext.act; direct assignment and STATE0_DETAIL readback.'; break }
        '^\$\.(ascension|floor|seed|turn|character)$' { $source='Settings.seed / AbstractDungeon.floorNum,ascensionLevel / GameActionManager.turn / AbstractPlayer.chosenClass; numeric or enum-name conversion.'; $cpp='BattleContext seed,floorNum,ascension,turn; character allowlist.'; break }
        '^\$\.capture_id$' { $source='Independent UUID generated at Java serialization; sidecar manifest binds UUID to serialized snapshot SHA-256.'; $cpp='Transport-only parser validation; deliberately not stored in BattleContext.'; break }
        '^\$\.room_context' { $source='AbstractRoom concrete class, event, eliteTrigger; Dead Adventurer fields only for rejected event context.'; $cpp='Parser validates supported MonsterRoomElite provenance; no room object stored.'; break }
        '^\$\.rng\.' { $source='AbstractDungeon.<stream>; Random.random RandomXS128.getState(0/1), Random.counter; stream id is static field name.'; $cpp='BattleContext Random seed0/seed1/counter.'; break }
        '^\$\.player' { $source='AbstractPlayer / AbstractCreature / EnergyPanel; powers and counters need individual conversion rules.'; $cpp='BattleContext.player and Player status/counter fields.'; break }
        '^\$\.piles' { $source='AbstractPlayer CardGroup.group order; AbstractCard.uuid/cardID/cost/costForTurn/timesUpgraded/misc/flags.'; $cpp='CardManager piles; UUID retained in differential map, dense uniqueId assigned by fixed pile order.'; break }
        '^\$\.card_counters' { $source='Exact derivation from stable ordered piles: hand Normality/Pain/Blood, active-pile Strike count (hand+draw+discard; exhaust excluded), and per-pile Blood counts; matches CardManager add/remove hooks.'; $cpp='CardManager counters are recomputed from restored CardInstances and supplied protocol counters are validated against the derivation.'; break }
        '^\$\.relic' { $source='AbstractPlayer.relics; AbstractRelic.relicId/counter; specialized counter mapping needed.'; $cpp='Player relic bitset and named relic counters.'; break }
        '^\$\.potions' { $source='AbstractPlayer.potions; AbstractPotion.ID/slot; null slot must map to EMPTY_POTION_SLOT.'; $cpp='BattleContext potions/potionCount/potionCapacity.'; break }
        '^\$\.monsters' { $source='current AbstractRoom MonsterGroup original order; AbstractMonster/AbstractCreature fields; Sentry private AI fields.'; $cpp='MonsterGroup slots, Monster fields/statuses/unique powers.'; break }
        '^\$\.monster_group|^\$\.monster_turn_idx' { $source='MonsterGroup/action manager state candidate; no verified current probe capture or player-boundary conversion.'; $cpp='BattleContext MonsterGroup flags and monsterTurnIdx.'; break }
    }
    if ($status -eq 'VERIFIED') { $countVerified++ } elseif ($status -eq 'UNAVAILABLE') { $countUnavailable++ } else { $countUnverified++ }
    # Classification is independent of whether reflection or conversion code exists.
    # Anything used by the current engine model is required for this exact state;
    # optional mechanics are conditional, and stable boundary facts can be derived.
    $classification = 'REQUIRED'
    if ($path -match '^\$\.room_context\.(event_|elite_trigger)' -or
        $path -match '^\$\.player\.counters\.' -or
        $path -match '^\$\.player\.statuses\[\]' -or
        $path -match '^\$\.monsters\[\]\.statuses\[\]' -or
        $path -match '^\$\.monsters\[\]\.(unique_power|misc_info)' -or
        $path -match '^\$\.relic_counters' -or
        $path -match '^\$\.monster_group\.' -or
        $path -match '^\$\.player\.orb_slots$') { $classification = 'CONDITIONAL' }
    if ($path -match '^\$\.(queues_empty|action_queue_size|card_queue_size|stage|battle_over|end_turn_queued|turn_has_ended|skip_monster_turn)$') { $classification = 'DERIVABLE' }
    if ($path -match '^\$\.card_counters\.') { $classification = 'DERIVABLE' }
    if ($path -match '^\$\.capture_id$') { $classification = 'UNUSED' }
    if ($path -match '^\$\.monster_group\.(extra_roll|skip_turn)\[\]$') { $classification = 'CONDITIONAL' }
    $classificationCounts[$classification]++
    $classificationRationale = switch ($classification) {
        'REQUIRED' { 'Engine transition/root identity can depend on this value for the accepted combat state.'; break }
        'CONDITIONAL' { 'A supported relic, power, status, or encounter mechanism may read this; absence is safe only after that dependency is ruled out.'; break }
        'DERIVABLE' { if ($path -match '^\$\.card_counters\.') { 'Each value is unambiguously derived from stable restored piles using the same category predicates and lifecycle hooks as CardManager.' } else { 'Stable player-action boundary validation or another exact protocol value can determine this without ambiguity.' }; break }
        'BLOCKED' { 'No verified stable source or player-boundary reconstruction is established; reject until resolved.'; break }
        'UNUSED' { 'Transport correlation only; parser validates the UUID but the value is not restored into simulation or execution identity.'; break }
        default { 'No protocol path currently assigned to this category.' }
    }
    $mapStatus = if ($status -eq 'VERIFIED') { 'SOURCE_DECLARATION_CONFIRMED; protocol/C++ end-to-end mapping NOT VERIFIED' } elseif ($status -eq 'UNAVAILABLE') { 'UNAVAILABLE; must reject if applicable' } else { 'UNVERIFIED; must not infer' }
    $fixtureStatus = if ($fixtureStatuses.ContainsKey($path)) { $fixtureStatuses[$path] } else { 'NOT_RUN' }
    $rows.Add("| ``$path`` | $type | **$classification** | $classificationRationale | $source | $cpp | $evidence; $mapStatus | **$fixtureStatus** |")
}
$rows.Insert(0, '# `live_battle_state v1` Complete Field Mapping Audit')
$rows.Insert(1, '')
$rows.Insert(2, ('Canonical path-shape inventory from the protocol fixture: {0} rows; classifications REQUIRED={1}, CONDITIONAL={2}, DERIVABLE={3}, INTERNAL={4}, UNUSED={5}, BLOCKED={6}. Source statuses VERIFIED={7}, UNVERIFIED={8}, UNAVAILABLE={9}. Status VERIFIED means source/member evidence only, never end-to-end production mapping. Current probe still writes only a diagnostic report.' -f $fields.Count, $classificationCounts.REQUIRED, $classificationCounts.CONDITIONAL, $classificationCounts.DERIVABLE, $classificationCounts.INTERNAL, $classificationCounts.UNUSED, $classificationCounts.BLOCKED, $countVerified, $countUnverified, $countUnavailable))
$rows.Insert(3, '')
$rows.Add('')
$rows.Add('## Evidence boundaries')
$rows.Add('')
$rows.Add('- `VERIFIED` means a matching STS1 bytecode declaration/reflection source exists; RNG also has prior real no-advance sampling. This does not mean the production protocol mapping is wired end to end.')
$rows.Add('- `UNVERIFIED` means the protocol/C++ target exists, but source conversion or complete diff is not verified.')
$rows.Add('- `UNAVAILABLE` means the collector has no trusted source or cannot recover the semantics; protocol input must be rejected.')
$rows.Add('- Empty fixture arrays show `[]`; card item shape is defined by the populated `piles.hand[]` example and the C++ card parser. This is not a live comparison of every pile.')
$rows | Set-Content -LiteralPath $Output -Encoding UTF8
Write-Output ('FIELD_MANIFEST path_shapes={0} verified={1} unverified={2} unavailable={3} output={4}' -f $fields.Count, $countVerified, $countUnverified, $countUnavailable, $Output)
