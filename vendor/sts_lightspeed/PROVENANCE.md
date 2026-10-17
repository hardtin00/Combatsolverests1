# Imported engine snapshot

Upstream: https://github.com/gamerpuppy/sts_lightspeed (MIT, see LICENSE.md).

Imported on 2026-10-17 from the existing local `sts_lightspeed-master` source
used by this project's previously configured engine. This is a **locally modified
snapshot**, not a verified pristine upstream revision. No upstream commit ID was
available in that extracted directory. IMPORT_SHA256.txt records the exact files
at import time, before this repository's new changes.

Existing local changes include machine-readable plan output, game card/potion IDs,
and replay_search. Other historical changes have not been audited against upstream.
Do not replace this snapshot with upstream master without protocol/regression checks.

Only src/, include/, apps/test.cpp and the required JSON headers are included.
nlohmann/json is MIT licensed; its license is retained in json/LICENSE.MIT and
its header notices. Python bindings, training dependencies and upstream test/data
trees are intentionally not needed by this CLI target.

Changes made after import:
- Minimal CLI-only CMake target; MinGW runtime statically linked.
- `protocol` command advertises the CombatSolver1 CLI contract version 1.

In-progress changes in the 2026-10-18 source snapshot: protocol v2 includes
selection identities/bit encodings and replay_trace; Ancient Tea Set restore checks
the saved armed counter. These changes compile but have not completed dedicated
regression or live-game acceptance. Native selection execution is not implemented.

Build: tools/build/build-engine.ps1. Default parallelism is 2. CMake's incremental
build avoids recompiling unchanged files. No network downloads or model training.
The generated binary lives under build/engine and is not committed.

This snapshot's inherited coverage/RNG claims are not a live-game qualification.
