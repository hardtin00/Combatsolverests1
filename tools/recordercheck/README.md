# Root replay diff

Inspect one historical battle bundle without editing it:

```powershell
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 `
  -BundlePath "$env:USERPROFILE\.combatsolver1\recordings\battle-..." `
  -EnginePath build\engine\combatsolver1-engine.exe
```

The command writes a machine-readable JSON report followed by one concise `SUMMARY` line. The report keeps five results separate: archive/file hashes, JSONL/journal alignment and state-chain gaps, C++ replay protocol, per-action stable-state comparisons, and the evidence level.

The checker verifies `manifest.json`, the three declared files and their SHA-256 values, manifest `actionCount`, the JSONL root/action sequence, journal action kinds, card hand/target identity metadata, and terminal unpaired-action metadata. Potion actions are identified but currently stop strict action alignment because this JSONL schema does not expose enough payload to verify their slot and target. A missing file, invalid hash, malformed JSON, or missing root blocks engine comparison. An action mismatch caps the comparable action prefix at the first mismatch. `journalDirty=true`, an open manifest, a state-chain break, or an unpaired terminal action limits the evidence to `diagnostic_only`; the tool never clears dirty state or edits a bundle.

For each journal prefix the checker starts an independent `replay_trace` run from the same `engine-in.save` and retains that run's result. Only exit code 0 plus exactly `REPLAY_OK n=N` and `TRACE0..TRACEN` confirms prefix N. `REPLAY_INVALID n=N` reports the first refused action even when the engine uses a nonzero rejection exit code. Timeouts and other nonzero exits are execution errors; absent, conflicting, malformed, or incomplete protocol output is a protocol error. In the C++ CLI, TRACE0 is the initialized root and TRACEi is emitted only after action i executes, so native post i is compared only to TRACEi from the separately confirmed prefix i.

Visible comparable fields are root/action player HP, max HP, block, energy, the game/engine turn mapping (`game turn = engine turn + 1`), and draw/discard/exhaust pile counts. Turn-transition observations align the first native pre-action state of a game turn with the first confirmed engine TRACE after the mapped end-turn transition. Other fields, including card order, monster state, relic state, full powers, and RNG state, remain explicitly unavailable because `replay_trace` does not emit a complete matching state. A comparison with matching visible fields and any such unknown native fields is labeled `partial_match_with_unavailable_fields`, not full match. An intermediate state, missing stable post, or journal/JSONL action mismatch is `not_comparable` or a restricted boundary observation. A final journal-only action with no stable post is `terminal_not_comparable`; manifest outcome is reported separately and does not imply terminal full-state equality.

Run the production-function regression suite without a game installation:

```powershell
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 -SelfTest
```

The self-test uses synthetic bundles and injected engine responses. It exercises archive/hash gates, action alignment and dirty handling, legal and rejected protocol responses, timeout/crash/malformed output, trace-to-action association, terminal/missing posts, turn mapping, phase gating, unknown fields, first HP mismatch, and protection from a later failed prefix contaminating an earlier successful comparison.
