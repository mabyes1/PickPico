# MCPocket Remote Transport

This document records the remote-connectivity architecture used by MCPocket. It is developer-facing;
the root README should keep only the user-facing setup and product boundary.

## Goal

An MCP client must be able to reach an Android MCPocket node when the client and phone are on
unrelated networks, including a phone on cellular data behind carrier NAT.

The Android device must not require:

- an inbound public IP;
- port forwarding;
- a shared Wi-Fi network;
- an additional VPN application; or
- an inbound firewall exception.

## Current architecture

```text
External MCP client
        |
        | HTTPS
        v
MCPocket Relay
Cloudflare Worker + Durable Object
        ^
        | WSS (phone-initiated)
        |
Android MCPocket
        |
        | HTTP loopback
        v
127.0.0.1:8765/mcp
```

The Cloudflare implementation is a reference backend, not part of PickPico's conceptual identity.
The Android side should continue to treat it as a relay/remote-transport endpoint rather than as a
Cloudflare-specific core dependency.

## Routing

Each PickPico installation owns a persistent random node ID. The reference relay exposes routes of
the form:

```text
GET  /health
GET  /v1/nodes/<node-id>/status
POST /v1/nodes/<node-id>/mcp   # legacy public schema URL
POST /v2/nodes/<node-id>/mcp   # full compatibility schema URL
POST /v3/nodes/<node-id>/mcp   # current Thin MCP schema URL
```

The Android client opens an outbound WebSocket associated with its node ID. Incoming MCP requests
are correlated with that live socket and forwarded to PickPico's local MCP server. For `/v3`, the
relay adds `X-PickPico-Tool-Profile: thin-v1` to the loopback request so the same Android runtime can
serve a small stable top-level MCP registry while `/v1` and `/v2` retain compatibility behavior.

Canonical reference Worker from Android `0.16.1` onward:

```text
https://pickpico-relay.mcpocket.workers.dev
```

When a device first migrates from the historical `https://relay.mcpocket.workers.dev` base URL, PickPico rotates its persisted relay Node ID and relay secret before connecting to the new Worker. The old capability URL therefore stops identifying the migrated phone, while unrelated app settings and the local MCP bearer remain untouched.

## Authentication boundaries

There are deliberately two separate credentials with different jobs:

1. **Relay secret**: authenticates the phone when it registers its outbound relay WebSocket. It never
   appears in the public MCP URL.
2. **Local MCP bearer token**: authenticates the private loopback request at PickPico's Android MCP
   server. It stays on the phone and is injected by `RelayClient` only for the
   `127.0.0.1:8765/mcp` hop.

The public remote MCP endpoint is a **capability URL** containing a persistent high-entropy node ID.
Remote MCP clients therefore do not need a custom `Authorization` header. Anyone who possesses that
full remote URL can attempt to invoke the node while it is online, so the URL must be treated like a
credential and should not be posted publicly.

The relay secret, capability URL, and local MCP bearer token have distinct jobs so one transport
credential is not silently reused as another.

## Connection lifecycle

Expected Android behavior:

1. Start the local MCP foreground service.
2. If a relay URL is configured, connect outbound using WSS.
3. Authenticate the node using node ID + relay secret.
4. Report `CONNECTED` only after relay registration succeeds.
5. Reconnect with backoff after network changes or transient failures.
6. Keep the local LAN endpoint available independently of remote-relay state.

This model is intentionally friendly to Wi-Fi-to-cellular handoff: the phone re-establishes its
outbound socket while the public Agent-side HTTPS endpoint stays stable.

## Hackathon / demo deployment

Current project-operated demo relay:

```text
https://relay.mcpocket.workers.dev
```

For the hackathon build it is acceptable to preconfigure or manually use this endpoint. The demo is
small and controlled, so the immediate priority is reliable cross-network operation rather than
building a public multi-tenant relay service.

Before the event, verify at minimum:

- LAN MCP works;
- remote relay connects over Wi-Fi;
- the same remote MCP URL still works after the phone switches to 5G/cellular data;
- relay reconnects after a network handoff;
- direct LAN requests without the MCP bearer token remain rejected;
- stale/unknown node IDs do not reach another node;
- the remote capability URL works without custom HTTP headers while the local bearer remains private
  to the Android loopback hop.

## Reference relay deployment

The Cloudflare reference implementation lives in `relay/`.

Persistent deployment under the developer's own Cloudflare account:

```bash
cd relay
npm install
npx wrangler login
npx wrangler deploy
```

For evaluation, Wrangler can also create a temporary deployment. Use the URL returned by the
temporary deployment as MCPocket's **REMOTE RELAY** value. A temporary deployment is preferable to
silently routing open-source users through the project owner's relay account.

## Open-source release policy

The public repository should not imply that `relay.mcpocket.workers.dev` is an unlimited hosted
service. Before a general open-source release:

- make the relay URL explicitly configurable;
- keep the Cloudflare relay as a reference implementation;
- document persistent self-host deployment;
- document a temporary/evaluation deployment path;
- avoid embedding project-owner credentials or relay secrets;
- add request-size, timeout, rate-limit, and abuse controls before operating a public shared relay.

The open-source product boundary should be:

```text
MCPocket remote transport protocol
    + configurable relay endpoint
    + Cloudflare reference implementation
```

not:

```text
Every MCPocket installation permanently consumes the project owner's Cloudflare account.
```

## Other transports

Tailscale remains useful as a development/emergency fallback because it provides mature NAT traversal
and a private network with little custom infrastructure. It is not the default product path because
it requires the phone/client environment to participate in a Tailscale setup.

OpenAI Secure MCP Tunnel is conceptually close to the reverse-relay model and may be added as an
optional provider-specific remote transport. It should remain an adapter rather than replacing the
client-neutral MCPocket relay path, because MCPocket is intended to work with MCP clients outside a
single provider ecosystem.
