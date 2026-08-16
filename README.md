# MCPocket

MCPocket turns an Android phone into a manually controlled MCP execution node.

## Current milestone

- Start and stop a foreground MCP node from the app.
- Serve Streamable HTTP on `http://<phone-ip>:8765/mcp`.
- Authenticate every MCP POST with a per-start bearer token.
- Keep compatibility tools including `server_info`, `phone_status`, `phone_ring`, restricted `phone_exec`, and `phone_echo`.
- Expose a capability-oriented command runtime through `command_list`, `command_run`, and `command_status`.
- Support the legacy initialize flow and the MCP `2026-07-28` stateless discovery flow.
- Keep MCP protocol/transport separate from Android tool implementations through a tool registry.

Current command IDs:

- `node.info`
- `phone.status`
- `phone.ring`
- `phone.echo`
- `process.run` (predefined diagnostics only)

The older phone-specific MCP tools remain available for compatibility, but they execute through the
same command runtime. New capabilities should be added as commands rather than wiring new behavior
directly into the HTTP/MCP transport layer.

`phone_exec` only accepts predefined diagnostic command IDs; it does not accept a shell string or arbitrary arguments.

Arbitrary shell execution, ADB, FYT, Git, SSH, discovery, TLS, and UI polish are not implemented yet.

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
