# PicoADB diagnostics and Android compatibility validation

Date: 2026-10-08 (Asia/Taipei). Branch: `feat/picoadb-screen-lifecycle`.

## Baseline and tool schema

The branch was fetched at `fb8dbf444fe9ba9a003ec2cb99242c4001fe4399`.
[PR #2 CI](https://github.com/mabyes1/PickPico/actions/runs/37730257376)
reported successful Android build/unit tests and relay unit tests for that commit.

Live relay v4 `tools/list` contains `picoadb_status`, `picoadb_discover`, and
`picoadb_battery_diagnostics`. Its `command_run.commandId` is a string with
runtime validation, not an enum. The connector definitions loaded into this
conversation still contained the old enum and omitted the direct tools. No
server-schema or pairing rewrite was needed.

## Changes

- Add `DashboardPicoAdbStatusTest`: create the real Home activity, confirm the
  Settings/PicoADB views have not been inflated, then call `refreshStatus`.
- Stream privileged diagnostic stdout/stderr directly to its workspace file.
  The old 4 MiB in-memory collector silently cut off `jobscheduler` while
  reporting success. Responses still contain metadata and bounded failure
  excerpts. Any failed diagnostic marks the bundle `partial` / `isError`.
- Build `0.17.5` / versionCode `131` with 16 KB-aligned native libraries.
  Node remains 18.20.4, using the Capawesome rebuild of upstream's 16 KB branch.
  Replace the old LADB binary with the pinned AOSP-derived build that includes
  `adb pair`; rebuild the JNI bridge with both 16 KB linker flags.
- Add an APK alignment check to CI, covering each ARM64 LOAD segment, APK entry
  alignment when uncompressed, and RELRO rounding over writable data. The old
  APK fails the check; the new APK passes. Four focused checker regression
  cases cover safe padding and unsafe overlaps.

Dependency origins and digests are in `THIRD_PARTY_NOTICES.md`. No binary header
patching or compatibility-warning suppression is used.

Final local validation: **227 Android tests, 0 failures, 0 errors**;
`:app:assembleDebug` successful; four alignment-checker regression tests passed;
all four packaged ARM64 libraries passed the checker and SDK `zipalign -c -P 16 4`.
The generated package metadata confirms `0.17.5` / `131`.

Deliverable: `artifacts/picoadb-0.17.5-20261008/PickPico-0.17.5-debug-16kb.apk`
(26509508 bytes). Changes are local and PR #2 remains draft; no merge or public
OTA publication was performed.

## Device observations and validation boundary

Device: Samsung S23 / SM-S9110, Android 16 / API 36. The device reports a current
page size of 4096 bytes. Its Android compatibility dialog explicitly identified
unaligned `libadb.so`, `libmcpocket_node_bridge.so`, and `libnode.so`, plus an
unspecified check error for `libc++_shared.so`.

The diagnostic-streaming patch was installed over 0.17.4 with `adb install -r`.
The pre-install APK, PicoADB identity, and preferences were backed up locally.
The app launched without an AndroidRuntime crash, retained its original pairing
timestamp/identity, and reconnected without a new pairing code.

The later 0.17.5 compatibility APK was installed in place on October 8 after
the user returned. The original pairing/identity persisted, the Dashboard was
visible, MCP/relay recovered, and the 16 KB compatibility dialog did not appear
on that launch. October 10 live `server_info` still reports 0.17.5. Native Node
execution passed the follow-up below. Automatic PicoADB reconnect remains
unverified; execution on a real 16 KB device also remains untested.

### Diagnostic files

All five commands returned exit code 0 without timeout on the installed
diagnostic-streaming patch at 13:19:27. Files remain in the phone workspace
under `diagnostics/2026-10-08T05-19-27.126736Z-*`.

| Output | Bytes reported by phone |
| --- | ---: |
| batterystats | 208331 |
| power | 230809 |
| deviceidle | 15096 |
| alarm | 420944 |
| jobscheduler | 4274961 |

The earlier `jobscheduler` file stopped mid-line at 4194303 bytes. The fixed
file exceeds that limit. USB disconnected before the new five-file bundle could
be copied back for byte-count/tail verification. Analysis below uses the earlier
local capture, whose first four files were not at the output limit.

Complete diagnostics were collected over local USB forwarding. An external
relay collection attempt was rejected by automatic approval review; no full
diagnostic payload was sent through that rejected call.

### Screen lifecycle

A real 4-second `process.exec` operation showed `activeOperations=1`,
`requested=true`, and `overlayHeld=true` while executing. Immediately after its
completion, the operation count was 0 and both overlay/wakelock flags were false.
Task count was 0. The phone's screen timeout setting remained 15000 ms.

The 360000 ms hung-operation watchdog passed the Robolectric simulated-time
test; it was not fault-injected into the live phone. The direct diagnostic
failure with Wireless Debugging off also returned to zero active operations.

During the October 8 collection, Wireless Debugging was initially off and was
temporarily enabled. USB disconnected before the restoration command ran, so
that run did not confirm the final toggle state. The user subsequently changed
Developer options; see the October 10 observations below. No pairing key was
cleared.

## October 10 live follow-up

Validation used the connected PickPico service and its phone-local Node/MCP
runtime. No desktop ADB connection or CONNECT button was used in this run.

- Node 18.20.4 on Android/arm64 executed JavaScript, SHA-256, filesystem
  write/read/remove, a child shell process, and authenticated loopback MCP
  requests successfully. The smoke program exited with code 0.
- The installed `libadb.so` executed `devices -l` successfully; the list was
  empty before the reconnect attempt. This confirms native execution but does
  not establish a wireless connection.
- The actual PickPico OPEN WIRELESS DEBUGGING button opened Samsung's
  Developer options page. The subsequent screenshots showed the master switch
  and Wireless Debugging on. The pre-click switch state was not measured, so
  this does not prove that the shortcut enabled either switch. This run did
  not click either system switch.
- Calling the fixed diagnostic tool over phone-local loopback triggered
  on-demand discovery. After 15 seconds it returned `picoadb_not_connected`,
  with no endpoint or files. A subsequent system property read reported
  `init.svc.adbd=stopped`. The pairing timestamp and last-connect timestamp
  were unchanged.
- Another GPT-6 task was concurrently operating the same phone. The user
  explicitly chose to let that other chat continue and requested this chat
  wrap up. The failed attempt is recorded; automatic reconnect is not marked
  passed or attributed to an engine defect from this overlapping run.

This chat's task was cancelled. Final read-only checks showed no running Node
test, no discovery, `activeOperations=0`, `requested=false`, `overlayHeld=false`,
and `wakeLockHeld=false`. `activeTasks=1` belonged to the other chat and was left
intact. Safe result metadata is saved beside the APK as
`live-validation-2026-10-10.json`; raw diagnostics and credentials were not
copied into that evidence file.

## Battery findings

The local capture at 13:14 reports a statistics start of **12:13:01 on October 8**,
only **1.343 seconds on battery**, zero screen-off time on battery, and charging
over USB. It does not contain a usable record of the previous night's 10% drop.

PickPico's estimated 19.6 mAh CPU contribution in that capture is almost entirely
in the plugged-in accounting buckets during development. It cannot be used as
an overnight standby-drain estimate. The live post-operation state showed no
PickPico screen hold; the contemporaneous power dump listed Samsung CMH and a
Google Play Services Wi-Fi scan partial wakelock. These are current observations,
not attribution of the previous night's drain.

The idle dump has `mCharging=true`, `mScreenOn=true`, and `mState=ACTIVE`. That is
consistent with the collection conditions and does not establish a Doze failure.
[Android's Doze documentation](https://developer.android.com/training/monitoring-device-state/doze-standby)
describes Doze under unplugged, screen-off inactivity.

No heartbeat intervals, notification rules, battery optimizations, or screen
timeout settings were changed. A useful next sample needs a normal unplugged
night, a recorded start/end battery level and time, and a morning bundle before
charging obscures the interval. Preserve the statistics rather than resetting
them. Compare screen-off uptime/Doze, UID wakelocks, alarms/jobs, and relay
counters over that same interval before attributing the drain.

## Packaging check references

- [Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)
- [Bionic RELRO page rounding](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp)

The prebuilt libraries have some RELRO ends below the next 16 KB boundary, but
the extra protected range contains padding rather than writable LOAD data. The
checker verifies that distinction against Bionic's outward rounding instead of
changing prebuilt ELF headers. Static layout checks do not replace native runtime
testing on a 16 KB device.
