# Real runtime RNG/Sentry probe result — 2026-10-19

## Evidence level and result

- **Runtime capture:** MTS 3.30.3 loaded the isolated probe after adding its required `ModTheSpire.json`; the user confirmed entering a stable Ironclad battle. The initial sample at `1792390763301` (Unix ms) met the queue gate but exposed the probe's bad `moveHistory` serializer. After correcting the probe and restarting through Steam, a second sample at `1792391438274` (Unix ms) captured a valid initialized Sentry state. Both samples were at COMBAT/player-input boundaries with `currentAction=null` and empty `actions`, `cardQueue`, and `monsterQueue`.
- **Six-stream read-only check:** In both samples, `aiRng`, `cardRandomRng`, `miscRng`, `monsterHpRng`, `potionRng`, and `shuffleRng` had identical before/after `seed0`, `seed1`, and `counter`. The six stream object identity hashes were distinct. The unredacted raw capture remains in ignored `build/rng-capture-validation/live-capture.tsv`; this report intentionally omits seed values.
- **Java/C++ offline replay from the corrected real capture:** JDK 6 compiled `JavaRngTrace`; MinGW compiled `cpp_rng_trace.cpp`. Each of the six corrected captured states ran the same 18-call sequence in the game Java 8 runtime and `sts::Random`. All 108 TSV rows matched exactly, including output bits/value, both state words, and counter. There was no first difference. Per-stream outputs and the sanitized comparison summary are under ignored `build/rng-capture-validation/`.
- **Boundary check:** `cpp_rng_boundary_check.cpp` compiled and ran successfully: `RNG_RANGE_BOUNDARY_OK rejected=INT_MIN,-1,INT_MAX accepted=0,INT_MAX-1`.

Commands and evidence (all exited 0):

```powershell
& 'C:\Program Files\Java\jdk1.6.0_43\bin\javac.exe' -source 1.6 -target 1.6 -d $classes tools/rng-capture-validation/JavaRngTrace.java
& $toolbin\g++.exe -std=c++17 -O0 -I vendor/sts_lightspeed/include -I tools/rng-capture-validation tools/rng-capture-validation/cpp_rng_trace.cpp -o build/rng-capture-validation/cpp_rng_trace_verified.exe
# For each capture TSV row: run Java 8 JavaRngTrace and cpp_rng_trace_verified.exe with its exact captured seed0, seed1, counter; Compare-Object returned no differences.
& $toolbin\g++.exe -std=c++17 -O0 -I tools/rng-capture-validation tools/rng-capture-validation/cpp_rng_boundary_check.cpp -o build/rng-capture-validation/cpp_rng_boundary_verified.exe
& build/rng-capture-validation/cpp_rng_boundary_verified.exe
```

Evidence files: `build/rng-capture-validation/live-capture.tsv`, six `*.java.tsv`/`*.cpp.tsv` pairs, and `trace-comparison.json` (`Rows=18`, `FirstDifference=none` per stream). These ignored local build outputs contain real seed words and are not committed.

## Sentry state and limitation

The corrected live object reflection returned three Sentries at 39/42/43 HP, group indices 0/1/2, `nextMove`/`moveHistory` values 3/4/3, intents DEBUFF/ATTACK/DEBUFF, `beamDmg=10`, `dazedAmt=3`, and `firstMove=false`. These are real field reads. Vanilla Sentry bytecode maps move 3 to its debuff/BOLT and 4 to its attack/BEAM, and selects the first move by encounter index parity. The C++ model's `getMoveForRoll` selects SENTRY_BOLT for even indices and SENTRY_BEAM for odd indices; `takeTurn` maps those moves to Dazed/attack with the same ascension thresholds. Thus this selected initial Sentry state is mapped consistently.

The first sample remains invalid for move-history mapping (`nextMove=-1`, `intent=DEBUG`); the corrected second sample supersedes it for Sentry mapping. JDK 6 `javac -source 1.6 -target 1.6` and archive metadata/stub-exclusion checks exited 0. The corrected temporary JAR was loaded in the restarted game, SHA-256 `0F0433AD8C429A28ECF5E8FB9ED2C61A25ED445279B5F7967BC5AD0835A997A4`.

The current screen was inspected without input: the hand contains Exhume, Defend, Strike, Bash, Strike; the only potion is Swift Potion (the other slot is empty). These actions do not expose a random-result event in this encounter, and the Sentry's observed initial moves are deterministic by position. No user-visible random game result was triggered or matched against an independent prediction; this remains the outstanding experiment requirement. No production protocol, solver, execution path, safety gate, official JAR, engine, player save, or notification configuration was changed.

The temporary probe is still loaded by the currently running game. Removing its file failed because Windows reports it is in use; it must be removed after the game exits normally. No process was forcibly closed. The official CombatSolver1 JAR hash remains `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`.

## Conclusion

**BLOCKED; route C is not GO yet.** The real capture established that reading all six RNG states is non-mutating, Java/C++ traces match, and the selected initialized Sentry state maps to C++ correctly. A real user-visible RNG event has not been correlated to its independent-copy prediction, so the runtime prediction requirement remains incomplete. This is missing evidence, not an observed RNG algorithm failure.
