# MCPocket POC

MCPocket turns an Android phone into a manually controlled MCP execution node.

## Scope

- Start and stop a foreground MCP node from the app.
- Serve Streamable HTTP on `http://<phone-ip>:8765/mcp`.
- Authenticate every MCP POST with a per-start bearer token.
- Expose `server_info` and the observable, allowlisted `phone_echo` action.
- Support the legacy initialize flow and the MCP `2026-07-28` stateless discovery flow.

Arbitrary shell execution, ADB, FYT, Git, SSH, discovery, TLS, and UI polish are intentionally out of scope.

## Build

The machine-wide developer environment must provide Java 17, Gradle 8.7, and Android SDK 35.

```powershell
cd D:\coding-tools-mcp\MCPocket
gradle :app:assembleDebug
```

Debug APK:

`app\build\outputs\apk\debug\app-debug.apk`

## Connect

1. Put the phone and MCP client on the same trusted LAN.
2. Open MCPocket and press **Start node**.
3. Copy the displayed connection JSON into an MCP client that supports custom HTTP headers.
4. List tools, call `server_info`, then call `phone_echo` with `{ "text": "hello phone" }`.

The node rejects requests without its bearer token and rejects non-loopback browser origins.
