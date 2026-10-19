# Real runtime RNG/Sentry probe result — 2026-10-19

## Evidence level and result

- **Runtime capture:** MTS 3.30.3 loaded the isolated probe after adding its required `ModTheSpire.json`; the user confirmed entering a stable Ironclad battle. Probe sample time: `1792390763301` (Unix milliseconds). The recorded room was COMBAT, the player turn was active, `currentAction` was null, and `actions`, `cardQueue`, and `monsterQueue` were empty.
- **Six-stream read-only check:** `aiRng`, `cardRandomRng`, `miscRng`, `monsterHpRng`, `potionRng`, and `shuffleRng` each recorded before/after `seed0`, `seed1`, and `counter`. All six triples were byte-for-byte unchanged. The six stream object identities were distinct. The unredacted raw capture remains in ignored `build/rng-capture-validation/live-capture.tsv`; this report intentionally omits seed values.
- **Java/C++ offline replay from this real capture:** JDK 6 compiled `JavaRngTrace`; MinGW compiled `cpp_rng_trace.cpp`. Each of the six captured states ran the same 18-call sequence in the game Java 8 runtime and `sts::Random`. All 108 TSV rows matched exactly, including output bits/value, both state words, and counter. There was no first difference. Per-stream outputs and the sanitized comparison summary are under ignored `build/rng-capture-validation/`.
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

The live object reflection returned three Sentries at 39/42/43 HP, `beamDmg=10`, `dazedAmt=3`, and `firstMove=false`. These are real field reads. The installed C++ model derives Sentry damage/status values from ascension thresholds and chooses its first move by encounter index when no move history exists; the vanilla Sentry bytecode uses the same even/odd encounter-position rule.

This sample does **not** validate the full current-move mapping. The probe serializer treated `moveHistory` only as an array, while the game field is `java.util.ArrayList`; its emitted `moveHistory=[]` is therefore invalid evidence. It also recorded `nextMove=-1` and `intent=DEBUG`, so this sample is not accepted as a completed initialized Sentry state. The isolated probe has been corrected to serialize iterable history, include encounter index, and wait until Sentry move/intent are initialized. JDK 6 `javac -source 1.6 -target 1.6` and archive metadata/stub-exclusion checks exited 0. Its corrected temporary JAR is deployed, SHA-256 `0F0433AD8C429A28ECF5E8FB9ED2C61A25ED445279B5F7967BC5AD0835A997A4`; it has not yet been loaded by a restarted game.

No real random game event was triggered and matched against the independent prediction. No production protocol, solver, execution path, safety gate, official JAR, engine, player save, or notification configuration was changed.

## Conclusion

**BLOCKED; route C is not GO.** The real capture established that reading all six RNG states is non-mutating, and the captured-state Java/C++ call traces are identical. Sentry's necessary move-history/current-intent mapping and an in-game random event remain unverified because of the probe serialization defect and current sample timing. Complete those two observations before deciding whether the minimum route C implementation is technically sound.
