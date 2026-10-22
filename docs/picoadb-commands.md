# PicoADB commands

PickPico 0.17.7 exposes Android ADB shell access, file transfer and APK installation through its saved Wireless Debugging identity. Enable Developer options and Wireless debugging in Android, then enable PickPico Hyper Mode. Commands follow the approval policy selected in PickPico.

## Pairing and connection

Settings and System Control show two independent rows:

- `PAIRING · SAVED`: PickPico retains its pairing and private ADB identity.
- `CONNECTION · CONNECTED`: a recent `adb devices -l` check found this phone's exact serial in the `device` state.
- `NOT CONNECTED`, `OFFLINE`, `UNAUTHORIZED`, `CHECKING`, or `CHECK FAILED`: the transport is unavailable or has not been verified.

Pairing survives a disconnected transport and an in-place app update. A historical connection timestamp does not imply a current connection. Visible Settings/System Control checks the transport in the background. An inactive status expires after 10 seconds. Explicit `picoadb_status` performs a fresh check. ADB commands discover and reconnect on demand; Android Wireless debugging must be enabled.

Agents can enable Wireless debugging through PICO's accessibility UI tools while ADB is disconnected. Read `guide.get` with `guideId=picoadb.connect`, also discoverable with `capability_search` using `adb`, `wireless debugging` or `開發人員`. Open PickPico → Settings → Android system access / System Control → Android Wireless Debugging. If Android opens Developer options, locate Wireless debugging there. Inspect the switch, enable it if off, and handle the current-network confirmation within the user's authorization. Reuse the saved pairing, then select Reconnect now or run `picoadb.shell` with `id` to trigger automatic reconnect. Verify `connected=true` or successful shell UID 2000 before resuming the original task. Ask for human help only for a concrete blocked step such as owner authentication or unavailable UI access.

## Shell

Use direct tool `picoadb_shell`, or `command_run` with `commandId: "picoadb.shell"`:

```json
{"agent":"your actual model name","command":"id"}
```

The command is passed to Android's shell, so pipes, quoting and redirection work there. It runs with Android shell UID 2000 permissions. Android still enforces protected data and root requirements.

## ADB commands

Use direct tool `picoadb_exec`, or `command_run` with `commandId: "picoadb.exec"`. Supply an argument array beginning with the ADB command. PickPico supplies the ADB engine and target serial.

```json
{"agent":"your actual model name","args":["push","example.txt","/sdcard/Download/example.txt"]}
```

```json
{"agent":"your actual model name","args":["pull","/sdcard/Download/example.txt","downloaded.txt"]}
```

```json
{"agent":"your actual model name","args":["install","-r","app.apk"],"timeoutMs":120000}
```

Local paths refer to the **phone's PickPico workspace**, not the computer's filesystem. Stage text with `workspace.write`; stage APKs/binary files in the workspace through a phone program or transfer. Paths containing spaces remain individual array elements. The same entry point supports the embedded client's other ADB commands, such as `logcat -d` and shell commands, subject to Android permissions.

The Android-built ADB client omits its host-side `install` and `uninstall` implementations despite listing them in help. PickPico implements these operations through the same ADB transport: single-APK installation pushes to a unique temporary directory and calls `cmd package install`; uninstall calls `cmd package uninstall`. Temporary APKs are removed after installation. Standard package-manager flags such as `-r`, `-t`, `-d`, `-g`, and `--user` are forwarded. Host streaming, fast-deploy and incremental flags are unsupported. Split-package and atomic multi-package host commands are not provided by this engine; Android's package-manager session commands remain available through `picoadb_shell`.

Calls are non-interactive. `timeoutMs` defaults to 30000 and accepts 100–120000. `maxOutputBytes` defaults to 16384 and accepts 1024–262144. Results include `success`, `exitCode`, `timedOut`, inline UTF-8 output, `outputTruncated`, `bytes`, and `workspacePath`. Complete combined stdout/stderr is saved under `adb-output/`; use `workspace.read` to read more. An unsuccessful remote command returns its exit code and error flag.

## Client schema refresh

The phone and relay publish `picoadb_shell` and `picoadb_exec` in `tools/list`. A client retaining an older tool catalog may need its PickPico connector/session refreshed before those direct names appear. Check the live catalog before changing the server.
