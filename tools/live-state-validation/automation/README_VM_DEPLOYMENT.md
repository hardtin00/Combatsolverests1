# CombatSolver1 isolated STS1 automation

This folder contains an offline-first controller, a separate MTS test mod, and VM deployment instructions. It does not alter the production solver or its safety gates. The test mod uses the game's native new-run, event, map, and room lifecycle; it does not construct Java DTOs or battle objects.

## Isolation contract

Run the real mode only inside a disposable Windows guest with a separate virtual disk. Install Steam, Slay the Spire, ModTheSpire, BaseMod and the test assets inside that disk, using a Steam account that owns the game. Keep Steam userdata and the game's save/config/log directories inside the guest. Disable cloud synchronization for this guest account/game inside the guest, and do not sign that account into Steam on the host while the test is in progress. Do not map host drives, use `\\tsclient`, or provide host-writable shared folders. The guest must have no host-writable shared storage. Do not copy host saves, preferences, Steam userdata, or cloud metadata.

The controller requires an isolation attestation file on the guest disk. That file is an operator assertion and is not by itself proof: the controller also checks that Windows identifies a VM, rejects mapped/network drives and visible host-writable shares, requires every configured path to be inside the explicit test root, and requires the test save directory to be empty before launch. If these checks cannot establish isolation, `Run` refuses to start. It never disables Steam Cloud or changes Steam settings. Normal Steam entitlement and Steam's configured “Play with Mods” launch route remain required.

## Prepare a guest

1. Create a disposable Windows VM with a separate virtual disk. Do not attach host writable folders or disks.
2. Install a legally owned copy of Steam and STS1 in `D:\CS1AutomationVM\Steam` (or choose another dedicated guest-only root and update every path in the config). Keep the Steam client, userdata, workshop files, game, test checkout, and engine within the guest's isolated disks.
3. Sign in to Steam only inside the guest. Install ModTheSpire and BaseMod there through normal supported means. Configure the game's Steam launch option inside the guest to use the MTS “Play with Mods” route; the controller does not edit Steam configuration.
4. Install the built `CS1LiveStateProbe.jar` and `CS1AutomationTestMod.jar` in the guest game's `mods` directory. Keep the formal CombatSolver1 JAR out of this test profile.
5. Place the current C++ Release engine in the configured engine path. Create empty, separate `saves`, `preferences`, and `logs` directories under the guest game root. Confirm no host paths are mounted.
6. Copy the project checkout, `automation-config-v1.json`, and the isolation attestation to a directory under the guest test root (for example `D:\CS1AutomationVM\workspace`). Replace paths with actual guest paths. The `Run` command rejects a config or controller script outside the test root. The test save directory must remain empty; the controller will not copy or delete files there.
7. Build the test mod using `build-testmod.ps1` with explicit game, MTS, BaseMod, and JDK paths. Deploy only the probe and test mod using `deploy-vm-assets.ps1 -Mode Deploy` from inside the guest.

After both JARs exist inside the guest checkout under `D:\CS1AutomationVM`, deploy with explicit source paths:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\live-state-validation\automation\deploy-vm-assets.ps1 `
  -Mode Deploy `
  -Config 'D:\CS1AutomationVM\workspace\tools\live-state-validation\automation\automation-config-v1.json' `
  -ProbeSource 'D:\CS1AutomationVM\workspace\build\live-state-validation\CS1LiveStateProbe.jar' `
  -TestModSource 'D:\CS1AutomationVM\workspace\build\live-state-validation\automation\CS1AutomationTestMod.jar'
```

Example attestation (fill truthfully; any false or missing value blocks real mode):

```json
{
  "schema_version": 1,
  "vm_guest": true,
  "separate_virtual_disk": true,
  "no_host_writable_shares": true,
  "isolated_steam_userdata": true,
  "steam_cloud_isolated": true,
  "legal_steam_entitlement": true,
  "launcher_mode": "STEAM_PLAY_WITH_MODS"
}
```

## Build and run

Build the isolated mod (does not install or launch it):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\live-state-validation\automation\build-testmod.ps1 `
  -GameDir 'D:\CS1AutomationVM\Steam\steamapps\common\SlayTheSpire' `
  -MtsJar 'D:\CS1AutomationVM\Steam\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar' `
  -BaseModJar 'D:\CS1AutomationVM\Steam\steamapps\workshop\content\646570\1605833019\BaseMod.jar' `
  -Javac (Join-Path $env:JAVA_HOME 'bin\javac.exe') `
  -JarTool (Join-Path $env:JAVA_HOME 'bin\jar.exe')

powershell -ExecutionPolicy Bypass -File .\tools\live-state-validation\build-probe.ps1 `
  -GameDir 'D:\CS1AutomationVM\Steam\steamapps\common\SlayTheSpire' `
  -Javac (Join-Path $env:JAVA_HOME 'bin\javac.exe') `
  -JarTool (Join-Path $env:JAVA_HOME 'bin\jar.exe')
```

Dry-run (safe on the development host; never starts a process):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\live-state-validation\automation\automation-controller.ps1 -Mode DryRun -Config .\tools\live-state-validation\automation\automation-config-v1.json
```

Run the full guest acceptance flow only after the guest and attestation are ready:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\live-state-validation\automation\automation-controller.ps1 -Mode Run -Config D:\CS1AutomationVM\automation-config-v1.json
```

`Run` starts the configured Steam client with the explicit app launch arguments and passes the probe output directory via `CS1_LIVE_STATE_OUTPUT_DIR`. It refuses an already-running Steam process because the existing process would not inherit that environment. After isolation checks, it creates only the configured empty guest save/preferences/log/output directories. The automation mod starts a new Ironclad run through native menu and character-selection paths, then follows native Neow/map transitions toward a normal `MonsterRoom` and validates the real one-monster Jaw Worm roster after room entry. It does not play cards. A scene outside the configured vanilla class allowlist is recorded as blocked.

The controller waits for a fresh `live-capture-report.json`. It calls `mcts_state` only if the probe also produced a complete `live-battle-state-v1.json` and the report says `PROTOCOL_CAPTURED`. It never sends `live-battle-diagnostic-v1.json` to C++ search. No search or execution is started in this phase. Output is written to a unique run directory and includes source report, hashes, C++ output, and `automation-acceptance-report.json`.

Offline component checks:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\live-state-validation\automation\automation-controller.ps1 -SelfTest
```

The automated battle creation and real state capture are not validated until a disposable VM run produces fresh MTS, mod, probe, and C++ evidence. A successful build or dry-run is not L3/L4 evidence.
