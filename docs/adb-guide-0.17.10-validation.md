# PICO wireless ADB guide — installed validation

Validated on Samsung SM-S9110, Android 16, 2026-10-11 (Asia/Taipei).

- Installed PickPico **0.17.10 / versionCode 136** with `adb install -r`; Android returned `Success`, and both package metadata and live `server_info` confirm the new version.
- Full Gradle unit suite and debug assembly passed: **247 tests, 0 failures, 0 errors**. `git diff --check` passed.
- APK SHA-256: `773DFACC10469F93EA96C9709568A900362AD55809FFE5AFC41687CF3E690F54`.
- Before installation, app preferences and the embedded ADB identity were archived locally under `C:\Users\ken\.codex\backups\pickpico-0.17.10-20261011`. Private backup contents are not included in repository evidence.
- Before/after byte comparisons match for ADB private/public keys, known hosts, approval policy, appearance, and caller-return preferences. Saved pairing fields match; original pairing time remains `2026-10-08T04:34:14.676352Z`.
- Node preferences differ only in operational fields: `relay_heartbeat_elapsed`, `media_foreground_requested`, `call_count`, and `recent`. Other node fields are preserved.
- Live relay `tools/list` exposes `picoadb.connect` in both `command_run` and `picoadb_status` descriptions. Live `capability_search` for `wireless debugging` returns the guide, and `guide.get` returns its five decision branches successfully.
- Updated phone's `picoadb_shell` successfully executes `id`: exit 0, UID 2000 (shell), using the retained pairing.
- PICO's Settings → System Control → Open Wireless Debugging Settings entry was used to open Android Developer options. The device was subsequently observed with Wireless debugging enabled and connected; the switch transition itself was not attributed to this agent.
- The initial task was cleared by application replacement. The replacement verification task completed. Final cleanup reports `activeTasks=0`, `activeOperations=0`, `requested=false`, `overlayHeld=false`, `wakeLockHeld=false`.

APK and live tool catalog, guide, search, preservation, shell, and cleanup evidence are in `artifacts/adb-guide-0.17.10-20261011/`.
