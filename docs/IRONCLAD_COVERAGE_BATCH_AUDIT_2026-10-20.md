# Ironclad / potion / relic coverage and batch plan (2026-10-20)

## Scope and evidence rules

This is a source audit of the checked-in live-state producer, `live_battle_state v1`, the `sts_lightspeed` C++ catalogue/effect paths, and the current offline tests. It does not change solver behavior and does not count enum membership or a switch label as a verified effect. The exact base-card inventory is derived from `TrulyRandomCardPool::pool[IRONCLAD]` plus the three starter cards (`Bash`, `Strike_R`, `Defend_R`): 75 cards. `AscendersBane` is listed separately as a conditional run curse; `RitualDagger` is a mapped colorless card, not an Ironclad class card.

Evidence levels remain separate:

- **Source present** means a C++ dispatch or helper reference exists. It is not an assertion of matching vanilla semantics.
- **L2 state interop** means a synthetic Java-writer → protocol-file → C++ restore/readback fixture passed.
- **L3/L4** require an actual Mod launch / real battle record. This audit has no new game run or real accepted root snapshot.

## Current counts

| Surface | Catalogue / state input | Effect path | Offline evidence | Live root limit |
|---|---|---|---|---|
| Ironclad class cards | 75 base/starter IDs; 7 have Java→C++ state mapping. The extra mapped `RitualDagger` is colorless. | A direct `BattleContext` card dispatch case exists for all 75 class/starter IDs. Several branches carry TODOs; case presence is not accuracy. | The live-state check passes synthetic card-instance identity/state fixtures for the mapped slice; it is not a 75-card effect suite. | Any unmapped ID in any serialized pile rejects the whole root. Accepted instance state is limited to upgrade count 0/1, common instance fields, and the explicit special-data rules.
| Potions | 42 non-sentinel potion IDs in the C++ enum. The Java producer has a separate string allowlist; it includes off-class entries and has two exact-ID mismatches against the C++ wire map. | `drinkPotion` has a switch arm for each enum, but `Smoke Bomb` is an empty TODO arm; `Fairy Potion` is passive and intentionally not drinkable. | No per-potion comprehensive effect differential. The protocol fixture checks slot ID restoration, not every potion outcome. | Java writes only IDs/slots, not potency or special runtime state. Search enumerates usable potions, so an unmodeled effect must be rejected before search.
| Relics | 180 valid C++ relic IDs. Player bitsets and `mcts_state` importer represent only indices 0–127 (128 IDs); 52 higher valid IDs cannot be restored. | Source references to relic IDs are scattered across battle start/end, player damage/power hooks, and game/run code. A reference is only a lead for review, not proof of complete behavior. | The live fixture reads back a small subset and named counters; it does not test the relic catalogue. | The Java producer currently accepts exactly one relic: Ironclad `Burning Blood`; it emits no relic counters. Other actual roots reject.
| Real battle state | Protocol audit has 149 path shapes: 99 REQUIRED, 35 CONDITIONAL, 14 DERIVABLE, 1 UNUSED. | C++ can restore the synthetic accepted subset and emit path detail. | Latest focused fixture: 148 MATCH, 0 MISMATCH, 0 UNAVAILABLE, 1 NOT_APPLICABLE (`capture_id`). Historical battle regression is not the same snapshot or a complete state proof. | Narrow untouched first player turn, no player powers, `Burning Blood` only, strict room/roster, and mapped card/potion states. No real snapshot has passed C++ full-root comparison.

The source audit file still records 83 `VERIFIED`, 14 `UNVERIFIED`, and 52 `UNAVAILABLE`; these are source/member audit labels, not 83 end-to-end mappings. The 149-path `MATCH` report is synthetic and cannot upgrade those source/live evidence levels. `LIVE_BATTLE_STATE_V1.md` and the field-audit document contain older “diagnostic-only/no producer” wording; the current `LiveBattleStateProbe.protocolFromSource()` and `writeProtocol()` do contain a strict protocol-producing path. The correct current statement is: **producer code exists for a narrow allowlist, but real snapshot → C++ root acceptance is still unverified.**

## Card matrix and mechanism groups

The exhaustive 75-card table below is generated from the repository's C++ Ironclad pool plus starter IDs and aligned `CardId`/`cardStringIds` tables. `direct=yes` records a C++ card dispatch case only. State-map support means the Java mapping, serializer, C++ importer allowlist, and L2 card readback slice are wired. No class card in this table has a per-card L4 effect verdict.

The 75 rows are an ID/adapter inventory, not a full 75-class desktop-1.0.jar constructor-bytecode and numeric base/upgrade audit. Therefore no row infers effect correctness from matching card names or dispatch presence; ordinary/upgraded vanilla values remain a required per-card gate before admitting an unmapped ID. The effect-regression column deliberately marks this as not established rather than treating the aggregate test suite as 75 independent passes.

Mechanism flags are source-review cues, not a support claim:

| Group | Cards / cases requiring separate batch tests |
|---|---|
| Dynamic choice or pile mutation | `Armaments`, `Headbutt`, `True Grit`, `Warcry`, `Burning Pact`, `Second Wind`, `Dual Wield`, `Fiend Fire`, `Exhume`; validate exact candidate identity/order and resulting piles. `True Grit` additionally depends on same-call RNG/candidate capture. |
| RNG or generated / automatic play | `Havoc`, `Infernal Blade`, `Sword Boomerang`; verify the relevant RNG stream, generated card identity, target selection, and nested action order. `Havoc` state mapping exists, but its live C++ effect is not L4-verified. |
| Power or turn timing | `Berserk`, `Brutality`, `Barricade`, `Corruption`, `Combust`, `Demon Form`, `Dark Embrace`, `Evolve`, `Feel No Pain`, `Fire Breathing`, `Inflame`, `Juggernaut`, `Metallicize`, `Rage`, `Rupture`; also any card applying timed debuffs/powers. Requires complete power amount and `justApplied` state where C++ transitions read it. |
| Multi-hit / scaling / cumulative special state | `Pummel`, `Twin Strike`, `Sword Boomerang`, `Heavy Blade`, `Perfected Strike`, `Rampage`, `Whirlwind`; cover block break between hits, strength multipliers, card-count scaling, X-cost and evolving per-instance damage. C++ source has TODOs for `Twin Strike` edge cases and `Rampage` damage progression. |
| Exhaust / Ethereal / status creation | `Carnage`, `Sentinel`, `Sever Soul`, `Second Wind`, `Fiend Fire`, `Power Through`, `Wild Strike`, `Immolate` and conditional curses/status cards; validate exhaust triggers, generated instances, ethereal end-turn behavior and UUID preservation. The importer currently rejects source `ethereal=true`. |
| Special instance data | `Searing Blow` upgrade depth and `RitualDagger` accumulated damage are explicit mappings. `Rampage` has UUID-linked evolving damage with no verified equivalence in `CardInstance.specialData`. |

### Base/starter card rows

| STS1 ID | C++ CardId | Identity / serialization / restore | Variant state accepted | C++ effect evidence | Mechanism / current gap |
|---|---|---|---|---|---|
| `Sword Boomerang` | `SWORD_BOOMERANG` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | RNG/GENERATED |
| `Perfected Strike` | `PERFECTED_STRIKE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | SCALING/UUID |
| `Heavy Blade` | `HEAVY_BLADE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | SCALING/UUID |
| `Wild Strike` | `WILD_STRIKE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CREATES_STATUS |
| `Headbutt` | `HEADBUTT` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Havoc` | `HAVOC` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades; misc=0 and ethereal=false except explicit special-data mapping | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | RNG/GENERATED |
| `Armaments` | `ARMAMENTS` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Clothesline` | `CLOTHESLINE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Twin Strike` | `TWIN_STRIKE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | MULTI-HIT/X |
| `Pommel Strike` | `POMMEL_STRIKE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Thunderclap` | `THUNDERCLAP` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Clash` | `CLASH` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Shrug It Off` | `SHRUG_IT_OFF` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades; misc=0 and ethereal=false except explicit special-data mapping | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `True Grit` | `TRUE_GRIT` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades; misc=0 and ethereal=false except explicit special-data mapping | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Body Slam` | `BODY_SLAM` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Iron Wave` | `IRON_WAVE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Flex` | `FLEX` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Warcry` | `WARCRY` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Cleave` | `CLEAVE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Anger` | `ANGER` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Evolve` | `EVOLVE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Uppercut` | `UPPERCUT` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Ghostly Armor` | `GHOSTLY_ARMOR` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | ETHEREAL |
| `Fire Breathing` | `FIRE_BREATHING` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Dropkick` | `DROPKICK` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Carnage` | `CARNAGE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | ETHEREAL |
| `Bloodletting` | `BLOODLETTING` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Rupture` | `RUPTURE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Second Wind` | `SECOND_WIND` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Searing Blow` | `SEARING_BLOW` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades only; special_data=upgrade_count; deeper upgrade rejected | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | SPECIAL_DATA |
| `Battle Trance` | `BATTLE_TRANCE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Sentinel` | `SENTINEL` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Entrench` | `ENTRENCH` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Rage` | `RAGE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Feel No Pain` | `FEEL_NO_PAIN` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Disarm` | `DISARM` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Seeing Red` | `SEEING_RED` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Dark Embrace` | `DARK_EMBRACE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Combust` | `COMBUST` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Whirlwind` | `WHIRLWIND` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | MULTI-HIT/X |
| `Sever Soul` | `SEVER_SOUL` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Rampage` | `RAMPAGE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | SCALING/UUID |
| `Shockwave` | `SHOCKWAVE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Metallicize` | `METALLICIZE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Burning Pact` | `BURNING_PACT` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Pummel` | `PUMMEL` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | MULTI-HIT/X |
| `Flame Barrier` | `FLAME_BARRIER` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Blood for Blood` | `BLOOD_FOR_BLOOD` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Intimidate` | `INTIMIDATE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Hemokinesis` | `HEMOKINESIS` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Reckless Charge` | `RECKLESS_CHARGE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CREATES_STATUS |
| `Infernal Blade` | `INFERNAL_BLADE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | RNG/GENERATED |
| `Dual Wield` | `DUAL_WIELD` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Power Through` | `POWER_THROUGH` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CREATES_STATUS |
| `Inflame` | `INFLAME` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Spot Weakness` | `SPOT_WEAKNESS` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Double Tap` | `DOUBLE_TAP` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Demon Form` | `DEMON_FORM` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Bludgeon` | `BLUDGEON` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Feed` | `FEED` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Limit Break` | `LIMIT_BREAK` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Corruption` | `CORRUPTION` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Barricade` | `BARRICADE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Fiend Fire` | `FIEND_FIRE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Berserk` | `BERSERK` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Impervious` | `IMPERVIOUS` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Juggernaut` | `JUGGERNAUT` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Brutality` | `BRUTALITY` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | POWER |
| `Reaper` | `REAPER` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Exhume` | `EXHUME` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CHOICE/PILE |
| `Offering` | `OFFERING` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Immolate` | `IMMOLATE` | Not admitted; Java producer/C++ importer reject this ID | No live state serialization; variants not accepted | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | CREATES_STATUS |
| `Bash` | `BASH` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades; misc=0 and ethereal=false except explicit special-data mapping | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Strike_R` | `STRIKE_RED` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades; misc=0 and ethereal=false except explicit special-data mapping | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |
| `Defend_R` | `DEFEND_RED` | Java→v1→C++ identity/state roundtrip is wired; synthetic L2 only | 0/1 upgrades; misc=0 and ethereal=false except explicit special-data mapping | BattleContext.cpp dispatch exists; card-by-card effect regression coverage is not established here; no L4 | No special marker in this scan; accuracy still unverified |

`AscendersBane` is not in the 75-card class/starter denominator. It has a C++ enum and generic curse/Ethereal lifecycle treatment, but no live map; negative cost and `ethereal=true` are rejected by the current serializer. `RitualDagger` is outside the Ironclad pool and is included only as an explicitly mapped colorless input. The six other colorless/curses/status definitions are not implicitly enabled by their C++ enum entries.


The mapped set among these 75 is exactly `Strike_R`, `Defend_R`, `Bash`, `True Grit`, `Shrug It Off`, `Havoc`, and `Searing Blow` (7/75). `RitualDagger` is the eighth Java/C++ wire mapping but is colorless and outside this class denominator. A mapped identity means the narrow instance fields survive the synthetic roundtrip; it does not certify the card effect. No card in this inventory has a complete per-card L4 effect verdict.
### Potion matrix

The C++ Ironclad potion pool contains 33 IDs. The Java producer currently allows 42 active enum strings plus the empty-slot marker, but it does not restrict them to the Ironclad pool. It omits the actual C++ wire IDs `FearPotion` and `Swift Potion`, while admitting `Fear Potion` and `SwiftPotion`; these will respectively reject a valid native ID or pass Java only to be rejected by the C++ parser. It also accepts nine potion IDs outside the current Ironclad pool; this is identity acceptance, not evidence that those mechanics are valid for Ironclad.

`BattleContext::drinkPotion()` has a case label for all 42 non-sentinel enum IDs. `Smoke Bomb` is a no-op TODO even though the searcher's potion enumerator can offer it; `Fairy Potion` is a passive death-save and is not a manually drinkable potion. Choice/random cases such as Attack/Skill/Power/Colorless Potion, Distilled Chaos, Entropic Brew, Gambler's Brew, Liquid Memories and Snecko Oil need dedicated action, generated-card, pile and RNG regression. No per-potion effect suite currently establishes the full set.

| C++ potion ID | C++ wire ID | In Ironclad pool | Java producer string accepted | C++ `drinkPotion` case | Assessment |
|---|---|---:|---:|---:|---|
| `INVALID` | `null` | no | no | yes | sentinel; Java rejects; C++ generic enum lookup can identify it |
| `EMPTY_POTION_SLOT` | `Potion Slot` | no | yes | yes | empty slot marker |
| `AMBROSIA` | `Ambrosia` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `ANCIENT_POTION` | `Ancient Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `ATTACK_POTION` | `AttackPotion` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `BLESSING_OF_THE_FORGE` | `BlessingOfTheForge` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `BLOCK_POTION` | `Block Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `BLOOD_POTION` | `BloodPotion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `BOTTLED_MIRACLE` | `BottledMiracle` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `COLORLESS_POTION` | `ColorlessPotion` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `CULTIST_POTION` | `CultistPotion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `CUNNING_POTION` | `CunningPotion` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `DEXTERITY_POTION` | `Dexterity Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `DISTILLED_CHAOS` | `DistilledChaos` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `DUPLICATION_POTION` | `DuplicationPotion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `ELIXIR_POTION` | `ElixirPotion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `ENERGY_POTION` | `Energy Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `ENTROPIC_BREW` | `EntropicBrew` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `ESSENCE_OF_DARKNESS` | `EssenceOfDarkness` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `ESSENCE_OF_STEEL` | `EssenceOfSteel` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `EXPLOSIVE_POTION` | `Explosive Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `FAIRY_POTION` | `FairyPotion` | yes | yes | yes | passive death-save; manual drink rejected; dedicated lifecycle path |
| `FEAR_POTION` | `FearPotion` | yes | no | yes | C++ case present; no exhaustive per-potion differential |
| `FIRE_POTION` | `Fire Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `FLEX_POTION` | `SteroidPotion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `FOCUS_POTION` | `FocusPotion` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `FRUIT_JUICE` | `Fruit Juice` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `GAMBLERS_BREW` | `GamblersBrew` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `GHOST_IN_A_JAR` | `GhostInAJar` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `HEART_OF_IRON` | `HeartOfIron` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `LIQUID_BRONZE` | `LiquidBronze` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `LIQUID_MEMORIES` | `LiquidMemories` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `POISON_POTION` | `Poison Potion` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `POTION_OF_CAPACITY` | `PotionOfCapacity` | no | yes | yes | C++ case present; no exhaustive per-potion differential |
| `POWER_POTION` | `PowerPotion` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `REGEN_POTION` | `Regen Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `SKILL_POTION` | `SkillPotion` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `SMOKE_BOMB` | `SmokeBomb` | yes | yes | yes | case is TODO/no-op; search may enumerate it — unsafe effect gap |
| `SNECKO_OIL` | `SneckoOil` | yes | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `SPEED_POTION` | `SpeedPotion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `STANCE_POTION` | `StancePotion` | no | yes | yes | dynamic choice/random/action path; per-effect regression needed |
| `STRENGTH_POTION` | `Strength Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |
| `SWIFT_POTION` | `Swift Potion` | yes | no | yes | C++ case present; no exhaustive per-potion differential |
| `WEAK_POTION` | `Weak Potion` | yes | yes | yes | C++ case present; no exhaustive per-potion differential |

Potion root serialization preserves ordered slot IDs/capacity only. It does not carry native potency, class/runtime subtype fields, or other potion instance data. This is sufficient only when the accepted scenario proves those values are fixed by the mapped potion, run state and supported relic context. It is not a general exact potion-state mapping.

### Relic matrix

The table lists every C++ relic enum entry and exact C++ `relicIds[]` string. `hook ref` means a textual direct reference occurs in `BattleContext.cpp` or `Player.cpp`; it is a triage signal only, not an effect verdict. The importer can store IDs 0–127 in its two 64-bit bitsets; indices 128–179 fail closed. Only `BURNING_BLOOD` is currently admitted by the Java producer. The seven counter names parsed by the experimental C++ importer are `HAPPY_FLOWER`, `INCENSE_BURNER`, `INK_BOTTLE`, `INSERTER`, `NUNCHAKU`, `PEN_NIB`, `SUNDIAL`; the Java producer currently sends no counters because it accepts Burning Blood alone.

| C++ relic ID | C++ wire ID | Enum index | C++ bitset/import | Java producer | Direct battle/player hook ref | Counter path |
|---|---|---:|---|---|---:|---|
| `AKABEKO` | `Akabeko` | 0 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ART_OF_WAR` | `Art of War` | 1 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BIRD_FACED_URN` | `Bird Faced Urn` | 2 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BLOODY_IDOL` | `Bloody Idol` | 3 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BLUE_CANDLE` | `Blue Candle` | 4 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BRIMSTONE` | `Brimstone` | 5 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CALIPERS` | `Calipers` | 6 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CAPTAINS_WHEEL` | `CaptainsWheel` | 7 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CENTENNIAL_PUZZLE` | `Centennial Puzzle` | 8 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CERAMIC_FISH` | `CeramicFish` | 9 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CHAMPION_BELT` | `Champion Belt` | 10 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CHARONS_ASHES` | `Charon's Ashes` | 11 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CHEMICAL_X` | `Chemical X` | 12 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CLOAK_CLASP` | `CloakClasp` | 13 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `DARKSTONE_PERIAPT` | `Darkstone Periapt` | 14 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `DEAD_BRANCH` | `Dead Branch` | 15 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `DUALITY` | `Yang` | 16 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ECTOPLASM` | `Ectoplasm` | 17 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `EMOTION_CHIP` | `Emotion Chip` | 18 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `FROZEN_CORE` | `FrozenCore` | 19 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `FROZEN_EYE` | `Frozen Eye` | 20 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `GAMBLING_CHIP` | `Gambling Chip` | 21 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `GINGER` | `Ginger` | 22 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `GOLDEN_EYE` | `GoldenEye` | 23 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `GREMLIN_HORN` | `Gremlin Horn` | 24 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `HAND_DRILL` | `HandDrill` | 25 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `HAPPY_FLOWER` | `Happy Flower` | 26 | yes | no (producer rejects extra relics) | yes | named parser path |
| `HORN_CLEAT` | `HornCleat` | 27 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `HOVERING_KITE` | `HoveringKite` | 28 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `ICE_CREAM` | `Ice Cream` | 29 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `INCENSE_BURNER` | `Incense Burner` | 30 | yes | no (producer rejects extra relics) | yes | named parser path |
| `INK_BOTTLE` | `InkBottle` | 31 | yes | no (producer rejects extra relics) | yes | named parser path |
| `INSERTER` | `Inserter` | 32 | yes | no (producer rejects extra relics) | yes | named parser path |
| `KUNAI` | `Kunai` | 33 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `LETTER_OPENER` | `Letter Opener` | 34 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `LIZARD_TAIL` | `Lizard Tail` | 35 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MAGIC_FLOWER` | `Magic Flower` | 36 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MARK_OF_THE_BLOOM` | `Mark of the Bloom` | 37 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MEDICAL_KIT` | `Medical Kit` | 38 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MELANGE` | `Melange` | 39 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MERCURY_HOURGLASS` | `Mercury Hourglass` | 40 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MUMMIFIED_HAND` | `Mummified Hand` | 41 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `NECRONOMICON` | `Necronomicon` | 42 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `NILRYS_CODEX` | `Nilry's Codex` | 43 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `NUNCHAKU` | `Nunchaku` | 44 | yes | no (producer rejects extra relics) | yes | named parser path |
| `ODD_MUSHROOM` | `Odd Mushroom` | 45 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `OMAMORI` | `Omamori` | 46 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ORANGE_PELLETS` | `OrangePellets` | 47 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ORICHALCUM` | `Orichalcum` | 48 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ORNAMENTAL_FAN` | `Ornamental Fan` | 49 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `PAPER_KRANE` | `Paper Crane` | 50 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `PAPER_PHROG` | `Paper Frog` | 51 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `PEN_NIB` | `Pen Nib` | 52 | yes | no (producer rejects extra relics) | yes | named parser path |
| `PHILOSOPHERS_STONE` | `Philosopher's Stone` | 53 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `POCKETWATCH` | `Pocketwatch` | 54 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RED_SKULL` | `Red Skull` | 55 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RUNIC_CUBE` | `Runic Cube` | 56 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RUNIC_DOME` | `Runic Dome` | 57 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RUNIC_PYRAMID` | `Runic Pyramid` | 58 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SACRED_BARK` | `SacredBark` | 59 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SELF_FORMING_CLAY` | `Self Forming Clay` | 60 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SHURIKEN` | `Shuriken` | 61 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SNECKO_EYE` | `Snecko Eye` | 62 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SNECKO_SKULL` | `Snake Skull` | 63 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `SOZU` | `Sozu` | 64 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `STONE_CALENDAR` | `StoneCalendar` | 65 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `STRANGE_SPOON` | `Strange Spoon` | 66 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `STRIKE_DUMMY` | `StrikeDummy` | 67 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SUNDIAL` | `Sundial` | 68 | yes | no (producer rejects extra relics) | yes | named parser path |
| `THE_ABACUS` | `TheAbacus` | 69 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `THE_BOOT` | `Boot` | 70 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `THE_SPECIMEN` | `The Specimen` | 71 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TINGSHA` | `Tingsha` | 72 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TOOLBOX` | `Toolbox` | 73 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `TORII` | `Torii` | 74 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `TOUGH_BANDAGES` | `Tough Bandages` | 75 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TOY_ORNITHOPTER` | `Toy Ornithopter` | 76 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TUNGSTEN_ROD` | `TungstenRod` | 77 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `TURNIP` | `Turnip` | 78 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TWISTED_FUNNEL` | `TwistedFunnel` | 79 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `UNCEASING_TOP` | `Unceasing Top` | 80 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `VELVET_CHOKER` | `Velvet Choker` | 81 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `VIOLET_LOTUS` | `VioletLotus` | 82 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `WARPED_TONGS` | `WarpedTongs` | 83 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `WRIST_BLADE` | `WristBlade` | 84 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BLACK_BLOOD` | `Black Blood` | 85 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BURNING_BLOOD` | `Burning Blood` | 86 | yes | Burning Blood only | yes | not in 7-counter parser |
| `MEAT_ON_THE_BONE` | `Meat on the Bone` | 87 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `FACE_OF_CLERIC` | `FaceOfCleric` | 88 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `ANCHOR` | `Anchor` | 89 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ANCIENT_TEA_SET` | `Ancient Tea Set` | 90 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BAG_OF_MARBLES` | `Bag of Marbles` | 91 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BAG_OF_PREPARATION` | `Bag of Preparation` | 92 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BLOOD_VIAL` | `Blood Vial` | 93 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BOTTLED_FLAME` | `Bottled Flame` | 94 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `BOTTLED_LIGHTNING` | `Bottled Lightning` | 95 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `BOTTLED_TORNADO` | `Bottled Tornado` | 96 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `BRONZE_SCALES` | `Bronze Scales` | 97 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `BUSTED_CROWN` | `Busted Crown` | 98 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CLOCKWORK_SOUVENIR` | `ClockworkSouvenir` | 99 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `COFFEE_DRIPPER` | `Coffee Dripper` | 100 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CRACKED_CORE` | `Cracked Core` | 101 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `CURSED_KEY` | `Cursed Key` | 102 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `DAMARU` | `Damaru` | 103 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `DATA_DISK` | `DataDisk` | 104 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `DU_VU_DOLL` | `Du-Vu Doll` | 105 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ENCHIRIDION` | `Enchiridion` | 106 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `FOSSILIZED_HELIX` | `FossilizedHelix` | 107 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `FUSION_HAMMER` | `Fusion Hammer` | 108 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `GIRYA` | `Girya` | 109 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `GOLD_PLATED_CABLES` | `Cables` | 110 | yes | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `GREMLIN_VISAGE` | `GremlinMask` | 111 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `HOLY_WATER` | `HolyWater` | 112 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `LANTERN` | `Lantern` | 113 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MARK_OF_PAIN` | `Mark of Pain` | 114 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `MUTAGENIC_STRENGTH` | `MutagenicStrength` | 115 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `NEOWS_LAMENT` | `NeowsBlessing` | 116 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `NINJA_SCROLL` | `Ninja Scroll` | 117 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `NUCLEAR_BATTERY` | `Nuclear Battery` | 118 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ODDLY_SMOOTH_STONE` | `Oddly Smooth Stone` | 119 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `PANTOGRAPH` | `Pantograph` | 120 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `PRESERVED_INSECT` | `PreservedInsect` | 121 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `PURE_WATER` | `PureWater` | 122 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RED_MASK` | `Red Mask` | 123 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RING_OF_THE_SERPENT` | `Ring of the Serpent` | 124 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RING_OF_THE_SNAKE` | `Ring of the Snake` | 125 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `RUNIC_CAPACITOR` | `Runic Capacitor` | 126 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SLAVERS_COLLAR` | `SlaversCollar` | 127 | yes | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SLING_OF_COURAGE` | `Sling` | 128 | no: index >=128 | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `SYMBIOTIC_VIRUS` | `Symbiotic Virus` | 129 | no: index >=128 | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `TEARDROP_LOCKET` | `TeardropLocket` | 130 | no: index >=128 | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `THREAD_AND_NEEDLE` | `Thread and Needle` | 131 | no: index >=128 | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `VAJRA` | `Vajra` | 132 | no: index >=128 | no (producer rejects extra relics) | yes | not in 7-counter parser |
| `ASTROLABE` | `Astrolabe` | 133 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `BLACK_STAR` | `Black Star` | 134 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CALLING_BELL` | `Calling Bell` | 135 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CAULDRON` | `Cauldron` | 136 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CULTIST_HEADPIECE` | `CultistMask` | 137 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `DOLLYS_MIRROR` | `DollysMirror` | 138 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `DREAM_CATCHER` | `Dream Catcher` | 139 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `EMPTY_CAGE` | `Empty Cage` | 140 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `ETERNAL_FEATHER` | `Eternal Feather` | 141 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `FROZEN_EGG` | `Frozen Egg 2` | 142 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `GOLDEN_IDOL` | `Golden Idol` | 143 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `JUZU_BRACELET` | `Juzu Bracelet` | 144 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `LEES_WAFFLE` | `Lee's Waffle` | 145 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `MANGO` | `Mango` | 146 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `MATRYOSHKA` | `Matryoshka` | 147 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `MAW_BANK` | `MawBank` | 148 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `MEAL_TICKET` | `MealTicket` | 149 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `MEMBERSHIP_CARD` | `Membership Card` | 150 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `MOLTEN_EGG` | `Molten Egg 2` | 151 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `NLOTHS_GIFT` | `Nloth's Gift` | 152 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `NLOTHS_HUNGRY_FACE` | `NlothsMask` | 153 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `OLD_COIN` | `Old Coin` | 154 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `ORRERY` | `Orrery` | 155 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `PANDORAS_BOX` | `Pandora's Box` | 156 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `PEACE_PIPE` | `Peace Pipe` | 157 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `PEAR` | `Pear` | 158 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `POTION_BELT` | `Potion Belt` | 159 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `PRAYER_WHEEL` | `Prayer Wheel` | 160 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `PRISMATIC_SHARD` | `PrismaticShard` | 161 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `QUESTION_CARD` | `Question Card` | 162 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `REGAL_PILLOW` | `Regal Pillow` | 163 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `SSSERPENT_HEAD` | `SsserpentHead` | 164 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `SHOVEL` | `Shovel` | 165 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `SINGING_BOWL` | `Singing Bowl` | 166 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `SMILING_MASK` | `Smiling Mask` | 167 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `SPIRIT_POOP` | `Spirit Poop` | 168 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `STRAWBERRY` | `Strawberry` | 169 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `THE_COURIER` | `The Courier` | 170 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TINY_CHEST` | `Tiny Chest` | 171 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TINY_HOUSE` | `Tiny House` | 172 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `TOXIC_EGG` | `Toxic Egg 2` | 173 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `WAR_PAINT` | `War Paint` | 174 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `WHETSTONE` | `Whetstone` | 175 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `WHITE_BEAST_STATUE` | `White Beast Statue` | 176 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `WING_BOOTS` | `WingedGreaves` | 177 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `CIRCLET` | `Circlet` | 178 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `RED_CIRCLET` | `Red Circlet` | 179 | no: index >=128 | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |
| `INVALID` | `INVALID` | 180 | sentinel | no (producer rejects extra relics) | no direct ref found | not in 7-counter parser |

## Live battle state: current independent blockers

Card additions alone cannot make a complete real snapshot. The current implementation remains fail-closed at all of these boundaries:

1. **Scenario gate:** vanilla Ironclad; exact map `MonsterRoomElite` or the separately identified Dead Adventurer `EventRoom` source; exact three vanilla Sentries; stable `PLAYER_NORMAL` with no current action, queued actions/cards/monsters, screen or unresolved choice. The event path is combat-only and stops at battle victory; it does not model event callbacks or elite rewards.
2. **Root-history gate:** only turn 1, no cards played, no combat damage/discards; player powers must be empty. This avoids restoring unsupported power/counter timing; it also excludes Metallicize, Corruption, and any existing Weak/Frail/Vulnerable/Double Damage/Draw Reduction root even though some class models and offline timing tests exist.
3. **Card state gate:** all four piles must contain only exact mapped IDs (seven Ironclad IDs, plus the colorless Ritual Dagger case), UUIDs must be unique full UUIDs, upgrade count must be 0/1, and all `misc`/Ethereal values must match their explicit rules. Any other native Ironclad card, curse/status card, multi-upgrade Searing Blow, or unrecognized special state rejects the complete root.
4. **Potion state/effect gate:** protocol source reads potion ID and slot only. Two exact native IDs do not agree across Java and C++; class-specific/off-class entries are not filtered; Smoke Bomb effect is incomplete. Current Burning Blood-only relic restriction avoids Sacred Bark, but broad potion support would need potency/effect-state validation.
5. **Relic gate:** producer accepts exactly Burning Blood and emits no relic counters. The C++ importer has only 128 relic bits although the enum has 180 valid IDs; only seven named counter slots are parsed. General relic/power counters and relic-trigger ordering are not a completed root map.
6. **Battle identity/context:** `capture_id` is a transaction UUID bound to bytes/hash, not a game-native battle identity. Room origin is separately encoded, but an accepted real snapshot has not yet been recovered by C++ and compared from game memory. Do not treat the synthetic 149-path fixture as that result.
7. **RNG and full-field evidence:** prior experiments separately established no-advance reads for six native RNG streams and 108-step Java/C++ sequence parity; True Grit had a same-call event prediction match. Those facts do not prove that one current live `live_battle_state` snapshot was restored with all six RNG states and all other required fields. Real root `STATE0_DETAIL` remains absent.
8. **Schema audit drift:** the current 149 path shapes classify 99 required fields; older STATUS tasks refer to 100 and the field audit contains historical source labels. The latest fixture is 148/149 because `capture_id` is transport-only N/A. Treat this as offline contract evidence only and reconcile the canonical schema counts before claiming complete real input.

### Protocol-field blockers, independent of card coverage

| Protocol path / state family | Source and current adapter | Exact blocker / allowed narrow deduction | Consequence |
|---|---|---|---|
| `room_context`, character/act/encounter and stable phase | Probe reads native room/player/action-manager objects; C++ restores a typed source/context. | Only vanilla Ironclad, exact Monster Elite three-Sentry or separately typed Dead Adventurer three-Sentry, first untouched player action boundary. Event context is combat-only; post-event callback/rewards are outside the model. | Other source, room, roster, phase or queue rejects; victory is not a full event/run result. |
| `piles.*[]` card identity, order, `upgrade_count`, cost, `special_data`, `ethereal` | Probe reads `AbstractCard` UUID/cardID/timesUpgraded/cost/costForTurn/misc/flags; C++ restores UUID→dense-ID and emits card details. | Only the seven mapped Ironclad IDs plus colorless Ritual Dagger; upgrade count is currently 0/1; only explicit Searing Blow/Ritual Dagger special-data rules; `ethereal=true` is rejected. | Any other card/status/curse in any pile, duplicate UUID, unsupported variant or special field rejects the whole snapshot. |
| `player.powers[]` and `just_applied` | Probe has concrete native class/field adapters; C++ timing bits exist and readback fixtures exist. | The live root producer currently requires the player's power list to be empty. Previously verified separate power/RNG experiments do not change this root gate. | Any active player power rejects; cards that create powers still need effect/timing tests before their routes can be trusted. |
| `player.counters.*`, turn/history counters | Native counters are read where available; several current values are constrained by the producer. | Zero values are only accepted under the exact untouched turn-1/no-card/no-damage/no-discard guards; this is a bounded deduction, not a generic native counter map. | Later turns or any guard violation reject; no default-zero extension is permitted. |
| `rng.ai/card/misc/monster_hp/potion/shuffle.{seed0,seed1,counter}` | Native `Random` state read is known to be non-advancing; C++ has the matching random state fields. | Prior six-stream and 108-step tests were separate experiments. No single accepted live snapshot has yet passed all RNG fields through C++ root recovery and full readback. | RNG feasibility is not full-root or search validation. |
| `relics[]`, `relic_counters[]` | Probe can read native relic IDs/counters; C++ importer maps relic IDs and seven named counter types. | Protocol producer accepts only Burning Blood and writes no counters. C++ player bitsets/import path support only enum indices 0–127; 52 valid higher indices cannot be represented. | Any other held relic currently rejects; counter-bearing relics cannot be generalized by ID-only mapping. |
| `potions[]` | Probe reads native potion `ID`/slot; C++ restores ordered IDs. | Producer serializes only ID/slot, has two Java/C++ exact-ID spelling mismatches, admits nine IDs outside the Ironclad pool, and has no potency/instance-state adapter. | Fix canonical ID/state contract before widening; unknown/off-class or effect-incomplete potion roots must reject. `Smoke Bomb` must also be blocked as an action until its TODO effect is fixed. |
| `monster_group.*`, Sentry identity/intent/history/status | Probe maps the exact 3-Sentry roster, current intent/status and validated first-move-related fields; C++ root model/readback covers the Sentry fixture. | Group flags/cursor use narrow stable-root rules; only Sentry and the whitelisted source are represented. There is no accepted real root/full C++ diff yet. | Other monster/group states reject; fixture matches are not evidence of real state fidelity. |
| `capture_id`, `battle_id`, `$.act`, terminal flags | Capture UUID is transaction metadata; `act` and source provenance are restored/read back; turn cursor is C++ sentinel state at the accepted root. | `capture_id` is not a native battle identity. Victory termination is modeled for the event combat-only entry but no reward/event continuation exists. | Bind the report and protocol bytes by hash; do not use transaction ID as a battle credential or claim post-battle search. |
## Work classification (A–D)

| Class | What belongs here | How to proceed safely |
|---|---|---|
| **A — C++ effect path exists; wire/state map missing** | Most of the 68 unmapped Ironclad class cards have direct `BattleContext` dispatch cases. Some have explicit TODOs or depend on state outside current root. | Batch only after per-card vanilla rule review and state-variant review. Generated maps must keep an independent effect-readiness gate; never infer ready from a switch label. |
| **B — reusable mechanism families** | Fixed damage/block, strength/dexterity modifiers, multi-target iteration, draw and common status application; generic UUID/pile serialization; exact power and relic counter adapters once sources are verified. | Parameterize tests by mechanism and card values, but keep card-level fixtures for each routed card and adversarial boundary. Share mechanism code only when the game semantics truly match. |
| **C — new/independent battle mechanics** | Card selection with native candidate UI, generated cards and their identities, nested auto-play, per-card evolving state, exhaust/ethereal edge semantics, unmodeled potion choices/effects, ordered relic triggers. | Keep rejected until a dedicated model and action identity/replay tests exist. Do not admit the ID early. |
| **D — depends on unresolved live state/RNG/timing** | Player powers and `justApplied`, counters, broad relic state, potion potency, non-first-turn counts, unknown combat queue/selection state, other encounters/monsters. | First provide a trusted read/serialize/restore/readback source for each field. Otherwise reject root or dependent action, even if card effect code exists. |

## Can current code generate maps and parameterized tests?

Partially. `Cards.h` aligns `CardId`, `cardStringIds[]` and card metadata; `CardPools.h` defines the Ironclad pool; `Potions.h` and `Relics.h` expose parallel enum/name tables. That data can generate inventories and identity consistency checks. But the Java `CardIdWireMap`, C++ `live_battle_state.h` eight-card allowlist, Java potion allowlist, relic counter switch and hand-authored source fixtures duplicate capability decisions. Current tests are hand-built fixtures, not generated from actual STS1 card constructors, and C++ branch presence cannot safely generate effect-ready status.

Recommended tooling boundary: one reviewed capability manifest (native ID, engine enum, instance-state adapter, effect status, mechanism tags, exact variant limits, test group) plus a generator/check that compares against the C++ catalogue and emits Java/C++ allowlists and parameterized *state* fixtures. Keep effect readiness and L4 evidence as separately reviewed fields; a generated test may prove wire roundtrip, never game semantics.

## Dependency-ordered implementation plan

| Stage | Scope and dependency | Acceptance | Failure policy |
|---|---|---|---|
| 0. Capability source and generated audit | Reconcile 75-card set, exact native IDs, C++ tables and explicit state/effect manifest. No solver behavior change. | Every source card maps to one manifest row; generated Java/C++ maps agree; duplicate/missing IDs fail CI; current eight-card fixtures unchanged. | Unknown identity stays rejected; do not auto-enable cases. |
| 1. Batch A — existing deterministic C++ effects | Admit small groups with ordinary cost/upgrade/misc/Ethereal state, no choices/RNG/new persistent powers. Candidate first slice: `Cleave` and `Bludgeon`, after checking installed STS1 constructors and C++ values. | Per-card base/upgrade effects and identity/pile roundtrip tests; multi-target/target identity where applicable; negative input tests. | A card failing state or effect differential remains outside allowlist. |
| 2. Batch B — reusable mechanisms | Extend parameterized effect tests across damage/block, draw, multi-hit, status application and exhaust families once Stage 1 confirms harness quality. | Mechanism boundary matrix plus card-specific values for every admitted card; verify end-turn/status timing. | Reject any route crossing unmodeled mechanic; do not silently approximate. |
| 3. Batch C — special card mechanics | Separate tasks for selection, generated/auto-play cards, RNG choices, Ethereal/exhaust, scaling/per-instance state, and long-lived Powers. Depends on verified action and identity semantics. | Native candidate/order or RNG pre/post differential; generated instance UUID lifecycle; round-trip of all special data and action effects. | Fail closed on any unresolved choice, random state, nested action or power timing. |
| 4. Potion and relic state/effects | Separate potion/relic manifests, exact native ID aliases, potion potency/class state, relic identity/counters and trigger order. Depends on complete input/adapter plan. | Every Ironclad-reachable potion and relic has separate input-state and effect tests; dynamic potion cases compare candidates/RNG; counter fields restore/read back. | Reject unrecognized, off-class or incompletely modeled potion/relic; preserve source slot/counter. |
| 5. Live root completeness and RNG binding | Extend stable root only as field sources and C++ destinations are verified; preserve currently successful RNG read method. | Every REQUIRED applicable field has trusted source → serialization → restore → internal readback; unknowns produce no protocol file. | No default/estimated field; root rejected at first unavailable required state. |
| 6. Full offline diff | Build non-empty fixtures covering each admitted mechanism/card-state variant and rejection boundary. | All applicable required fields `MATCH`; zero required `MISMATCH/UNAVAILABLE`; N/A only by schema rule; `mcts_save` and replay regressions unchanged. | No admission when a required field is not compared. |
| 7. Real battle acceptance | Only after Stage 6: real source snapshot, SHA-256, dual source read, six RNG streams unchanged, C++ full `STATE0_DETAIL`, then separate search/execute approval. | Real root field-by-field match; then separately validate battle search; do not conflate this with L4 execution. | Stop and report exact first mismatch; no production execution on failed root. |

No calendar estimate is asserted: this repository has no measured per-card mapping/effect throughput, and the real root acceptance has not yet completed.

## Recommended first batch task

First implement Stage 0 as a small offline capability-manifest/generator check, then the first A slice should be **Cleave + Bludgeon**: both have direct C++ dispatch and fit ordinary instance state without a card-choice or random-card mechanism. Their base and upgraded values still need comparison with installed `desktop-1.0.jar`; each needs its own root readback and effect regression before the Java/C++ allowlists are widened. This tests the batch workflow on distinct single-target and all-enemy damage paths without conflating card coverage with the unresolved live-root fields.

This remains planning only. No production or probe changes are authorized by this audit.
