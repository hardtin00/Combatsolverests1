# Live state read-only source probe

This isolated ModTheSpire probe writes two distinct artifacts after its first
stable player-action boundary:

- `build/live-state-validation/live-capture-report.json` records source-state
  diagnostics, acceptance/rejection reason, per-card identity/state/effect
  classification, protocol file path and SHA-256.
- `build/live-state-validation/live-battle-state-v1.json` is emitted only when
  the strict `live_battle_state v1` checks pass. It is the file consumed by the
  C++ `mcts_state` command; the diagnostic report itself is never importable.

The currently supported live slice is deliberately narrow: exact vanilla
Ironclad in either a map `MonsterRoomElite` or the single allowlisted
`EventRoom` / `DeadAdventurer` enemy index 0 / `3 Sentries` context, with exactly
three original vanilla Sentries, on the first untouched player turn with stable
empty queues. These origins remain distinct in the protocol and C++ root.
Unsupported event choices, cards, relics, potions, powers, pile states or
unknown values are rejected. Search runs only in the restored battle context;
victory or loss ends the result without event continuation, event rewards or map
elite rewards. The probe reads game objects only; it does not invoke RNG draws,
read autosaves, mutate combat objects or modify saves. It reads all six RNG
states before and after, reads the source snapshot twice, and refuses output if
either comparison changes.

Card diagnostics continue across the whole hand/draw/discard/exhaust scan when
an individual ID or instance state is unsupported. Categories distinguish an
unsupported identity, a known identity with unmapped instance state, a captured
state whose effect is unmodeled, and a captured card with a C++ implementation
present. The latter is not a claim of L4 effect accuracy. The diagnostic
artifact never relaxes the Java writer or C++ parser; any item outside the
strict import/search allowlist prevents protocol generation or root restore.
Havoc's exact card identity and common instance fields are mapped in this slice;
its existing C++ play-top-card implementation has not been validated against a
real STS1 Havoc action.

The EventRoom protocol/root/search coverage is offline fixture evidence only.
The existing real capture report is a rejected diagnostic and no real EventRoom
protocol snapshot has yet passed C++ state recovery. A Java `PROTOCOL_CAPTURED`
report alone is not an end-to-end acceptance result.

Build the isolated JAR with:

```powershell
powershell -ExecutionPolicy Bypass -File tools/live-state-validation/build-probe.ps1
```

It writes `build/live-state-validation/CS1LiveStateProbe.jar` and checks Java 8
class major 52. Before loading it, run
`powershell -ExecutionPolicy Bypass -File tools/live-state-validation/audit-fields.ps1`.
To collect a real sample, close the game normally, temporarily put only this
probe JAR in the MTS `mods` directory, launch via Steam **Play with Mods**, and
enter a naturally occurring qualifying fight. Do not create or edit a save to
force an encounter. Remove the probe after normal game exit.
