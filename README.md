# MCPocket

MCPocket turns an Android phone into a manually controlled MCP execution node.

## Current milestone

- Start and stop a foreground MCP node from the app.
- Serve Streamable HTTP on `http://<phone-ip>:8765/mcp`.
- Authenticate every MCP POST with a per-start bearer token.
- Keep compatibility tools including `server_info`, `phone_status`, `phone_ring`, restricted `phone_exec`, and `phone_echo`.
- Expose a capability-oriented command runtime through `command_list`, `command_run`, and `command_status`.
- Keep a persistent private workspace under the MCPocket app data directory.
- Read, write, and recursively list workspace files without shell-escaping file contents.
- Resolve relative `exec_command.cwd` values below the workspace root.
- Keep long-running commands alive as managed background sessions with `read_output` and `kill_session`.
- Run workspace JavaScript through an APK-embedded Node.js runtime in an isolated `:node` Android process.
- Download and verify self-update APKs, then expose a foreground **Update MCPocket** button so Android's required install confirmation is user-initiated instead of relying on background popups.
- Support the legacy initialize flow and the MCP `2026-07-28` stateless discovery flow.
- Keep MCP protocol/transport separate from Android tool implementations through a tool registry.

Current command IDs:

- `node.info`
- `phone.status`
- `phone.ring`
- `phone.lock` (requires one-time Device Admin opt-in)
- `phone.echo`
- `workspace.info`
- `workspace.list`
- `workspace.read`
- `workspace.write`
- `node.start`
- `node.status`
- `node.stop`
- `app.update`
- `app.update_status`
- `process.run` (predefined diagnostics only)
- `process.exec` (general Linux shell execution inside the MCPocket app sandbox)
- `process.output`
- `process.stop`

MCP also exposes `exec_command` as a direct convenience tool for `process.exec`. It executes through
`/system/bin/sh -c` and supports optional `cwd`, `env`, `stdin`, `timeoutMs`, `maxOutputBytes`, and
`background`. The default working directory is MCPocket's private workspace root. Relative `cwd`
values resolve below that root; absolute paths remain available for advanced sandbox-visible paths.
Synchronous results keep `stdout` and `stderr` separate and report exit code, timeout, duration, and
output truncation.

With `background=true`, `exec_command` returns immediately with a `sessionId` and leaves the process
running. Use `read_output` to inspect its captured output/status and `kill_session` to stop it. This is
the foundation for dev servers and other long-lived project processes.

Workspace-native MCP tools are available as `workspace_info`, `workspace_list`,
`workspace_read_file`, and `workspace_write_file`. Workspace file paths are always relative and are
canonicalized so `..` traversal cannot escape the workspace root.

`process.exec` is intentionally powerful but it does not escape Android's app sandbox. Commands run
as the MCPocket app UID, not as ADB shell, root, or the Android system user.

Node.js is exposed separately from `exec_command`. Android does not treat MCPocket's writable
workspace as a place for executable binaries, so MCPocket packages `libnode.so` with the APK and
starts workspace JavaScript through JNI in an isolated `:node` service process. MCP tools
`node_start`, `node_status`, and `node_stop` manage that runtime without stopping the MCP server.

The current Node runtime is a bootstrap POC based on nodejs-mobile 18.20.4 for `arm64-v8a`. It has
been smoke-tested on a Samsung S23 by writing `server.js` through the MCP workspace API, starting it
with `node_start`, reaching its HTTP server over Wi-Fi, and then stopping the runtime process. It is
not yet the intended long-term Node distribution/version strategy.

Self-update candidates are downloaded into MCPocket private storage and are accepted only when the
SHA-256 matches, the package name is MCPocket, the signing certificate matches the installed app,
and the candidate version is newer. Once staged, the app UI shows the candidate version and enables
an update button. Android still owns the final install confirmation; MCPocket does not bypass it.

MCPocket can also update itself without ADB through `app_update`. The updater downloads an HTTP(S)
APK in the background, requires the exact SHA-256 up front, checks that the package name and signing
certificate match the installed MCPocket, rejects downgrades, and streams the verified APK into
Android's `PackageInstaller`. Android may require a one-time "Install unknown apps" grant for
MCPocket and then shows the system install/update confirmation UI. Progress and installer state are
available through `app_update_status`.

The older phone-specific MCP tools remain available for compatibility, but they execute through the
same command runtime. New capabilities should be added as commands rather than wiring new behavior
directly into the HTTP/MCP transport layer.

`phone_exec` remains the legacy restricted diagnostic tool. New general Linux command execution should
use `exec_command` or `command_run` with `process.exec`.

`phone.lock` never attempts to elevate itself. Until the user explicitly enables MCPocket's
force-lock Device Admin policy in the Android system UI, the command returns `requiresSetup=true`.

Git and Python toolchains are not bundled yet. `workspace_info` reports which executables are
actually visible to MCPocket's Android app sandbox; the embedded Node runtime is managed through the
dedicated Node commands instead of appearing as a shell executable. ADB, FYT, SSH, discovery,
remote relay, TLS, and UI polish are also not implemented yet.

## Workspace POC flow

Once the node is running, an MCP client can create and run a project entirely inside the phone:

1. Call `workspace_info` to discover the workspace root and available executables.
2. Call `workspace_write_file` with `path: "hello/run.sh"` and the project contents.
3. Call `exec_command` with `cwd: "hello"` and `command: "sh run.sh"`.
4. For a long-running process, add `background: true`, then poll `read_output` with the returned
   `sessionId` and stop it with `kill_session` when finished.

The workspace survives node restarts and normal app updates because it lives in MCPocket's app data.
It is removed if the app is uninstalled.

## Node workspace POC flow

1. Write a JavaScript entry point, for example `hello-node/server.js`, with `workspace_write_file`.
2. Call `node_start` with `{ "entry": "hello-node/server.js" }`.
3. Call `node_status` to verify the isolated `:node` process is running.
4. If the script opens a LAN server, connect directly to the phone IP and the script's port.
5. Call `node_stop` to terminate only the Node runtime while leaving the MCP node running.

The first build downloads the nodejs-mobile Android runtime archive and installs the configured NDK
and CMake versions through the Android Gradle toolchain when they are not already present.

## Build

The machine-wide developer environment must provide Java 17, Gradle 8.7, and Android SDK 35.

```powershell
cd D:\coding-tools-mcp\MCPocket
gradle :app:assembleDebug
```

Debug APK:

`app\build\outputs\apk\debug\app-debug.apk`

For local debug smoke tests, an ADB shell can ask the protected debug bridge to start the real MCP
node without touching the phone UI:

```powershell
pwsh scripts/dev-node-start.ps1
```

The debug bridge can then exercise the real authenticated loopback MCP transport without exposing
the bearer token to the host command line:

```powershell
pwsh scripts/dev-command.ps1 -CommandId phone.status
pwsh scripts/dev-command.ps1 -CommandId phone.ring -ArgumentsJson '{"durationSeconds":10}'
```

## Connect

1. Put the phone and MCP client on the same trusted LAN.
2. Open MCPocket and press **Start node**.
3. Copy the displayed connection JSON into an MCP client that supports custom HTTP headers.
4. List tools, call `server_info`, then call `phone_echo` with `{ "text": "hello phone" }`.

The node rejects requests without its bearer token and rejects non-loopback browser origins.

## ADB development bridge

Debug builds expose a tiny smoke-test bridge protected by Android's `DUMP` permission. Ordinary
third-party apps cannot invoke it, release builds ignore it, and the MCP HTTP authentication path
is unchanged. It exists so local development tools can trigger observable actions without copying
the node credential through terminal arguments.

```powershell
pwsh scripts/phone-find.ps1
pwsh scripts/phone-find.ps1 -DurationSeconds 30
pwsh scripts/phone-find.ps1 -Stop
```
