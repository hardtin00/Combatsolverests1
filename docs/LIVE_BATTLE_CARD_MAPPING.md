# Live card-instance mapping slice (offline, 2026-10-20)

This is an exact identity/state conversion slice, not a claim that every listed card effect has passed live simulation. The Java source mapper accepts only these ten installed STS1 `AbstractCard.cardID` values and translates them to the matching `sts_lightspeed::CardId` enum string:

| STS1 `cardID` | `CardId` | Evidence |
|---|---|---|
| `Strike_R` | `STRIKE_RED` | `cards.red.Strike_Red` constructor passes `Strike_R` to `AbstractCard.<init>` |
| `Defend_R` | `DEFEND_RED` | `cards.red.Defend_Red` constructor passes `Defend_R` |
| `Bash` | `BASH` | `cards.red.Bash` constructor passes `Bash` |
| `True Grit` | `TRUE_GRIT` | `cards.red.TrueGrit` constructor passes `True Grit` |
| `Shrug It Off` | `SHRUG_IT_OFF` | `cards.red.ShrugItOff` constructor passes `Shrug It Off`; base block 8, upgrade adds 3, `use` queues that block and one draw |
| `Havoc` | `HAVOC` | `cards.red.Havoc` constructor passes `Havoc`; common instance fields are captured from the live card |
| `Searing Blow` | `SEARING_BLOW` | `cards.red.SearingBlow(int)` constructor passes `Searing Blow` |
| `RitualDagger` | `RITUAL_DAGGER` | `cards.colorless.RitualDagger` constructor passes `RitualDagger` |
| `Cleave` | `CLEAVE` | `cards.red.Cleave` constructor passes `Cleave`; base damage 8, upgrade adds 3, all-enemy target |
| `Bludgeon` | `BLUDGEON` | `cards.red.Bludgeon` constructor passes `Bludgeon`; base damage 32, upgrade adds 10, cost 3, single-enemy target |

The mapping was checked with the installed `desktop-1.0.jar` using `javap -private -c` and against `vendor/sts_lightspeed/include/constants/Cards.h` (`CardId` and color table) plus `SaveFileMappings.h` (wire/save IDs). The names are matched by exact identifiers, not localized display names.

`uuid` is preserved in full. C++ validates UUID syntax and uniqueness over hand/draw/discard/exhaust, assigns dense IDs in that exact pile order, stores the ordered UUID↔dense-ID relation in the isolated `RestoredState` wrapper, and sets `CardInstance::uniqueId` to the matching dense ID. `STATE0_DETAIL_CARD` reads the UUID back through that restored relation and compares identity, pile/index, engine card ID, costs, upgrade count, and `special_data` against the input. The full UUID is deliberately not truncated into the 16-bit `CardInstance::uniqueId`.

`timesUpgraded` maps to `upgrade_count` and `upgraded`; only 0/1 is accepted. Searing Blow's live game object uses `timesUpgraded` as its upgrade depth; the C++ model stores that depth in `specialData`, so the importer maps `timesUpgraded` to `special_data` and requires native `misc == 0`. Ritual Dagger maps native `misc` to C++ `specialData`; the game constructor initializes this field as its base damage, and the C++ model consumes the same state as its damage base. All other accepted cards require `misc == 0`. `cost`, `costForTurn`, `freeToPlayOnce`, and `retain` map directly after type/range checks. Unsupported IDs, multi-upgrade states, UUID collisions, and unsupported `misc` values fail closed.

Havoc is now included in the narrow state map. The collector reads its full UUID, native ID, upgrade count, live `cost`/`costForTurn`, `misc`, retain and free-to-play flags; only upgrade counts 0/1 and `misc==0` are accepted. The C++ root stores the card in the exact pile/index and UUID-to-dense-ID relation. Its existing `BattleContext::playCard` branch queues `PlayTopCard`, and `playTopCardInDrawPile` removes the top card and marks it autoplay/free/exhaust. This proves an implementation path exists; it is not a real-game effect differential or L4 accuracy claim. Because the current import whitelist is also the search eligibility gate, other cards cannot be searched or executed from this protocol, even if their `CardId` enum exists.

The runtime diagnostic now reads each card's primitive instance fields independently and records one mapping result per card instead of aborting the pile scan on its first unsupported ID. Results distinguish `CAPTURABLE_AND_CXX_EFFECT_PRESENT`, `IDENTITY_KNOWN_STATE_UNMAPPED`, `STATE_CAPTURED_EFFECT_UNMODELED`, and `UNSUPPORTED_IDENTITY`; source-read errors are retained per instance. Diagnostics are only `live_battle_capture_report` data. They do not relax `writeProtocol`, and any unsupported instance still prevents a `live_battle_state v1` file.

Still unsupported: all IDs outside the explicit ten-card map; nonzero `misc` except Ritual Dagger; upgrade counts outside 0/1; and Rampage, whose live damage progression is modified by UUID and has no verified `CardInstance.specialData` equivalence. Non-Ironclad color cards remain rejected. The current prior real diagnostic did not preserve a source snapshot, so it cannot establish which additional IDs or pile states were present. C++ having a `CardId` or an effect switch alone does not make state mapping or effect accuracy verified.

## Cleave / Bludgeon batch 1 (L2 offline)

`Cleave`→`CLEAVE` and `Bludgeon`→`BLUDGEON` are added to the exact Java ID map and the C++ live-state importer. Their supported source state remains limited to upgrade count 0/1, `misc == 0`, and `ethereal == false`; unknown IDs and other states fail closed. Existing C++ effect branches were exercised for base and upgraded damage, target scope, energy cost, and card movement. Java→protocol→C++ offline fixture recovery matched 16/16 instances across four piles, including full UUID, upgrade, costs, and instance flags. This is L2 synthetic-fixture evidence only; real-game effect validation has not been run. Detailed results and the tested engine hash are recorded in `docs/IRONCLAD_CARD_BATCH_1_REPORT.md` and `docs/IRONCLAD_CARD_BATCH_1_MANIFEST.json`.

The fixtures `live_battle_cards_v1_source.json`, `live_battle_havoc_v1_source.json`, and `live_battle_shrug_v1_source.json` are marked offline-only. The Havoc fixture covers ordinary/upgraded instances, all four piles and multiple UUIDs for the same card ID. The Shrug fixture covers ordinary and upgraded instances, duplicate IDs with distinct full UUIDs, hand order, and a draw-pile instance. They are not real game captures and do not establish L3/L4.

## Seven-card candidate review and this one-card increment

The unregistered candidates were compared against the installed `desktop-1.0.jar`, current Java wire map/serializer, C++ importer, and C++ effect paths:

| Native ID / runtime class | Current C++ behavior evidence | Additional state/risk blocking a low-risk one-card slice |
|---|---|---|
| `Shrug It Off` / `cards.red.ShrugItOff` | `BattleContext::useSkillCard` grants 8/11 block and queues one draw; the card is included in the search agent's skill set. Installed bytecode constructor sets base block 8, `upgrade()` adds 3, and `use()` queues `GainBlockAction(block)` then `DrawCardAction(1)`. | No card-specific special data; source requires `misc=0`, `ethereal=false`, and ordinary 0/1 upgrade/cost fields. The draw still depends on the already imported draw pile and RNG. Selected as the smallest state-mapping gap.
| `Headbutt` / `cards.red.Headbutt` | Damage and a Headbutt action exist. | Selects a discard-pile card and places it atop draw; multi-candidate choice/action ordering needs separate effect validation.
| `Carnage` / `cards.red.Carnage` | 20/28 damage path and C++ Ethereal turn-end handling exist. | Native card is Ethereal; the current Java serializer rejects `ethereal=true`, so mapping it requires validating that C++ trait derivation exactly represents the source state.
| `Metallicize` / `cards.red.Metallicize` | Applies a 3/4 Metallicize power, which grants block at turn end. | Live-state importer currently rejects roots with active player powers; adding the card alone does not close that state dependency.
| `AscendersBane` / `cards.curses.AscendersBane` | Curse type/negative cost and C++ Ethereal handling exist. | Native state is Ethereal and has negative cost; serializer currently rejects Ethereal, so the full card-specific normalization needs dedicated proof.
| `Corruption` / `cards.red.Corruption` | Applies Corruption; this changes Skill cost and exhaust behavior globally. | The resulting active Power state is outside the current empty-player-powers root boundary and affects every Skill transition.
| `Doubt` / `cards.curses.Doubt` | C++ end-turn effect applies Weak through the no-trigger card path. | Requires the root Weak amount and `justApplied` timing. The current tree contains a bytecode-based timing review and regression; this does not constitute live effect validation.

The selected slice adds only the exact `Shrug It Off`→`SHRUG_IT_OFF` wire mapping and matching C++ importer admission. The existing generic serializer preserves full UUID, cost, cost-for-turn, upgrade count, free-to-play and retain flags; it permits only `misc=0` and rejects any source marked Ethereal. `STATE0_DETAIL_CARD` compares those values from the restored C++ `CardInstance` and its full-UUID bridge. Protocol schema and search/effect implementation are unchanged. The C++ branch's presence and this fixture round-trip are L2 evidence, not a real-game effect differential or proof of all draw/RNG behavior.
