# PicoADB 0.17.7 validation — 2026-10-10

## Implemented behavior

- Settings and System Control separate saved pairing from the currently verified ADB transport. Exact device-row parsing distinguishes `device`, `offline`, and `unauthorized`; old connection timestamps never mark a device connected.
- Hyper Mode exposes `picoadb_shell` and `picoadb_exec` with the existing arbitrary-process approval policy. Commands reconnect on demand with the existing identity.
- Shell commands preserve remote shell syntax and exit status. ADB file transfer uses argument vectors and the phone workspace. Complete output streams to disk, with a bounded inline excerpt.
- The Android-built engine omits native install/uninstall dispatch. The package adapter transfers a single APK through ADB, invokes Android `cmd package install`, and cleans its unique staging directory. Failed transfer aborts installation. Uninstall invokes Android's package manager through ADB shell. See [command guide](picoadb-commands.md) for scope and flags.
- Cold daemon startup receives a 10-second background-check deadline. The initial 3-second deadline was insufficient: the S23's measured startup took 3.19 seconds.
- `phone_home` reports an asynchronous keyguard-dismiss transition as pending. A secure keyguard alone no longer asserts that owner authentication is required. The caller must re-observe the final state.

## Build verification

Final version: 0.17.7 / versionCode 133.

- `:app:testDebugUnitTest :app:assembleDebug`: passed; 240 tests, zero failures or errors.
- Native alignment checker: all four ARM64 libraries pass 16 KB LOAD alignment, Bionic RELRO safety, and APK alignment checks.
- SDK 35 `zipalign -c -P 16 4`: passed.
- `git diff --check`: passed.
- APK SHA-256: `22693D8CC95E980FA42C56A2673DB81125CE9913E137EC92DAA02AEC578484F4`.

## Device acceptance

Device: Samsung S23 / SM-S9110, Android 16 / API 36. The device uses 4 KB memory pages; this is not a physical 16 KB-device test.

The intermediate 0.17.6 build was installed in place. Its original `lastPairAt` remained `2026-10-08T04:34:14.676352Z`, with the persistent private identity present. Both loopback and relay v4 `tools/list` exposed the two new direct tools. The saved-pairing / disconnected UI was inspected on the device.

Initial live execution verified shell UID 2000, remote exit code 7, and a 49-byte Chinese/emoji file roundtrip with matching SHA-256. This run exposed the embedded engine's missing native `install` command; its evidence is retained as `attempt-before-install-fix.json` under the dated artifacts directory. The test file was removed and no fixture package was installed during that failed attempt.

Final 0.17.7 / versionCode 133 was installed in place on 2026-10-11 at 03:14:05 UTC. The updater reports `installed`, the expected APK SHA-256, and the original signing certificate. Node and relay restored successfully. The original pairing timestamp and persistent identity remain intact. Safe updater evidence is saved as `installed-0.17.7-20261011.json` in the dated artifacts directory.

At 03:16 UTC the final build's first smoke attempt verified both tool catalogs, then stopped before shell execution because no Wireless debugging endpoint was advertised. `adbd` was running, while both the phone's discovery and the computer's mDNS inventory had no endpoint. A later fresh screenshot of Developer options showed Wireless debugging off and USB debugging on. No fixture file or package was created by this attempt.

After the owner enabled Wireless debugging, the final full smoke run passed all 16 checks from 03:20:54 to 03:21:01 UTC. PickPico discovered and connected to the new endpoint `192.168.0.15:46605` using its original identity. The computer only observed mDNS; shell/file/package operations ran through PickPico's embedded ADB engine and MCP tools on the phone. Evidence is retained as `live-adb-0.17.7-20261011.json` in the dated artifacts directory:

- Loopback and relay v4 tool catalogs expose `picoadb_shell` and `picoadb_exec`.
- Shell `id` returns UID 2000; an explicit remote exit 7 is preserved with its output and error flag.
- Push/pull of the 49-byte Chinese/emoji fixture succeeds with SHA-256 `177c232245061d063783dea0af9d3690f0632806dcc901f83c1ea390cd6fef5f`.
- The 8,538-byte harmless fixture APK's SHA-256 is verified before installation. `picoadb_exec install` returns `Success`, and `pm path com.mcpocket.adbsmoke` confirms the installed package.
- `picoadb_exec uninstall` returns `Success`; `pm path` confirms that only the test package is absent. The roundtrip file is removed.
- Final `picoadb_status` reports `paired: true`, `connected: true`, and the unchanged original pairing timestamp. The Node validation process exits 0.

Subsequent system port changes briefly made the old transport offline. An ordinary `picoadb_shell` invocation discovered the new port and reconnected automatically with the saved identity; no pairing or CONNECTION button was used. Safe reconnection evidence is saved as `reconnect-0.17.7-20261011.json`. Both Settings and System Control were observed with independent `PAIRING · SAVED` and `CONNECTION · CONNECTED` rows. The same installed build had earlier shown `NOT CONNECTED` and `OFFLINE` accurately. The connected System Control screen was visually inspected at 03:24 UTC; selected UI evidence is saved as `ui-0.17.7-20261011.json`.

The updated `phone_home` behavior was observed on the device: it returned `transitionPending: true` while requesting keyguard dismissal, and the subsequent UI inspection showed the unlocked One UI home screen. It did not prematurely request owner authentication.

Final cleanup on 2026-10-11 at 03:24:54 UTC verified `activeTasks=0`, `activeOperations=0`, `requested=false`, `overlayHeld=false`, and `wakeLockHeld=false`. Both owned validation Node runs exited 0. The temporary artifact server was stopped and port 18766 had no listener. The installed updater hash matches the local final APK. Original pairing and app settings remain preserved. Cleanup evidence is saved as `cleanup-0.17.7-20261011.json`. Idle ADB status expires to `unknown` after ten seconds; this is intentionally separate from the successful live connection and does not erase saved pairing.

Previous handoff on 2026-10-10 at 11:54 UTC: the phone updater had verified 0.17.7 and was awaiting the owner's Android installation confirmation; the installed version remained 0.17.6. The validation Node program had exited. The owned phone task was cancelled for that handoff, and `activeTasks=0`, `activeOperations=0`, `requested=false`, `overlayHeld=false`, `wakeLockHeld=false` were verified. The temporary LAN artifact server and the computer's test ADB connection were stopped. The smoke script downloads its harmless fixture APK from a temporary server on `192.168.0.20:18766`, serving only the dated artifact directory.

## Remaining investigation

The original overnight battery drain has not yet been attributed. Prior charged/short on-battery diagnostics are insufficient to identify the cause. Heartbeat and important notification settings were not changed as part of this work.
