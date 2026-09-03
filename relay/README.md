# MCPocket Relay

The relay makes a phone-hosted MCPocket node reachable across NATs and unrelated networks without
opening an inbound port on Android.

```text
Agent --HTTPS--> Cloudflare Worker / Durable Object --WSS--> MCPocket --HTTP loopback--> :8765/mcp
```

Each MCPocket installation generates a persistent random node ID and a separate relay secret. The
secret authenticates the phone's outbound WebSocket. The node ID is also the high-entropy capability
component of the public MCP URL. Remote clients therefore use the endpoint without HTTP
authentication; MCPocket injects its local bearer token only when replaying the request against the
phone's loopback MCP server.

## Deploy

The canonical Cloudflare Worker name in this repository is `relay`. Keep `wrangler.jsonc` aligned
with that name so a plain `wrangler deploy` updates the intended Worker instead of creating a second
service under a different hostname.

```bash
cd relay
npm install
npx wrangler login
npx wrangler deploy
```

Then paste the resulting `https://...workers.dev` URL into **REMOTE RELAY** in MCPocket and restart
the node. Once **RELAY STATUS** is `CONNECTED`, **COPY CONNECTION JSON** automatically uses the
remote HTTPS endpoint instead of the LAN endpoint.

For the current hackathon/demo environment, the project-operated relay is:

```text
https://relay.mcpocket.workers.dev
```

That endpoint is demo infrastructure, not an unlimited public relay commitment. Open-source users
should deploy this reference relay into their own Cloudflare account or use a temporary Wrangler
deployment for evaluation, then paste the returned URL into PickPico.

The important product boundary is the PickPico relay protocol, not Cloudflare itself. A different
backend can implement the same remote-transport contract later.

Useful checks:

```text
GET /health
GET /v1/nodes/<node-id>/status
POST /v1/nodes/<node-id>/mcp   # legacy public schema URL
POST /v2/nodes/<node-id>/mcp   # full compatibility schema URL
POST /v3/nodes/<node-id>/mcp   # current Thin MCP schema URL
```

## Update channel

The same Worker serves PickPico's stable self-update channel from a dedicated Workers KV binding
named `UPDATE_KV`:

```text
GET /v1/update/latest
GET /v1/update/files/releases/<versioned-apk>.apk
```

`latest.json` is deliberately tiny and uncached. APKs are split into versioned 20 MiB KV chunks and
streamed back as one immutable response. The Android app still validates SHA-256, package ID,
signing certificate, and versionCode before asking Android to install anything.

Publish from the repository root with:

```powershell
pwsh scripts/publish-update.ps1
```
