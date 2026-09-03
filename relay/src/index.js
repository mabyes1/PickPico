import { DurableObject } from "cloudflare:workers";

// HUMAN_HELP uses a renewable human-idle lease, so its total wall-clock wait
// can legitimately exceed one 360-second lease. This is only a transport
// safety net; the product-level timeout is enforced by PickPico itself.
const REQUEST_TIMEOUT_MS = 30 * 60_000;
const HEARTBEAT_STALE_MS = 35_000;
const DELIVERY_ACK_TIMEOUT_MS = 5_000;
const NODE_ID_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return json({ ok: true, service: "pickpico-relay" });
    }

    if (url.pathname === "/u.apk") {
      if (request.method !== "GET") {
        return json({ error: "method_not_allowed" }, 405, { Allow: "GET" });
      }
      const latestText = await env.UPDATE_KV.get("latest.json");
      if (!latestText) {
        return json({ error: "update_not_published" }, 404);
      }
      let latest;
      try {
        latest = JSON.parse(latestText);
      } catch (_) {
        return json({ error: "update_manifest_invalid" }, 500);
      }
      const apkUrl = typeof latest.apkUrl === "string" ? latest.apkUrl : "";
      if (!apkUrl.startsWith("https://") && !apkUrl.startsWith("http://")) {
        return json({ error: "update_manifest_invalid" }, 500);
      }
      return Response.redirect(apkUrl, 302);
    }

    if (url.pathname === "/v1/update/latest") {
      if (request.method !== "GET") {
        return json({ error: "method_not_allowed" }, 405, { Allow: "GET" });
      }
      const latest = await env.UPDATE_KV.get("latest.json");
      if (!latest) {
        return json({ error: "update_not_published" }, 404);
      }
      return new Response(latest, {
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": "no-store",
        },
      });
    }

    const updateFileMatch = url.pathname.match(/^\/v1\/update\/files\/(.+)$/);
    if (updateFileMatch) {
      if (request.method !== "GET") {
        return json({ error: "method_not_allowed" }, 405, { Allow: "GET" });
      }
      const key = decodeURIComponent(updateFileMatch[1]);
      if (!/^releases\/[A-Za-z0-9._-]+\.apk$/.test(key)) {
        return json({ error: "invalid_update_file" }, 400);
      }
      const metadataText = await env.UPDATE_KV.get(`${key}:meta`);
      if (!metadataText) {
        return json({ error: "update_file_not_found" }, 404);
      }
      let metadata;
      try {
        metadata = JSON.parse(metadataText);
      } catch (_) {
        return json({ error: "update_file_metadata_invalid" }, 500);
      }
      const chunkCount = Number(metadata.chunkCount);
      const totalBytes = Number(metadata.totalBytes);
      if (!Number.isInteger(chunkCount) || chunkCount < 1 || chunkCount > 64
          || !Number.isSafeInteger(totalBytes) || totalBytes < 1) {
        return json({ error: "update_file_metadata_invalid" }, 500);
      }

      const body = new ReadableStream({
        async start(controller) {
          try {
            for (let index = 0; index < chunkCount; index += 1) {
              const chunkKey = `${key}:chunk:${String(index).padStart(3, "0")}`;
              const chunk = await env.UPDATE_KV.get(chunkKey, "arrayBuffer");
              if (!chunk) {
                throw new Error(`missing chunk ${index}`);
              }
              controller.enqueue(new Uint8Array(chunk));
            }
            controller.close();
          } catch (error) {
            controller.error(error);
          }
        },
      });

      return new Response(body, {
        headers: {
          "content-type": "application/vnd.android.package-archive",
          "content-length": String(totalBytes),
          "cache-control": "public, max-age=31536000, immutable",
          "etag": `"${metadata.sha256 || key}"`,
        },
      });
    }

    // v1/v2 remain available for existing clients. v3 is the Thin MCP public schema
    // boundary: a small stable top-level tool set backed by dynamic capability discovery.
    const match = url.pathname.match(/^\/(v1|v2|v3)\/nodes\/([^/]+)\/(connect|mcp|status)$/);
    if (!match) {
      return json({ error: "not_found" }, 404);
    }

    const apiVersion = match[1];
    const nodeId = match[2];
    const action = match[3];
    if ((apiVersion === "v2" || apiVersion === "v3") && action !== "mcp") {
      return json({ error: "not_found" }, 404);
    }
    if (!NODE_ID_PATTERN.test(nodeId)) {
      return json({ error: "invalid_node_id" }, 400);
    }

    const stub = env.NODE_RELAY.get(env.NODE_RELAY.idFromName(nodeId));
    return stub.fetch(request);
  },
};

export class NodeRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
    this.pending = new Map();
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/connect")) {
      return this.connectNode(request);
    }
    if (url.pathname.endsWith("/status")) {
      const sockets = this.nodeSockets();
      const healthySockets = sockets.filter((socket) => this.isSocketHealthy(socket));
      const heartbeatAges = sockets
        .map((socket) => this.socketHeartbeatAgeMs(socket))
        .filter((value) => Number.isFinite(value));
      return json({
        online: healthySockets.length > 0,
        connectedSockets: sockets.length,
        healthySockets: healthySockets.length,
        lastHeartbeatAgeMs: heartbeatAges.length ? Math.min(...heartbeatAges) : null,
        pendingRequests: this.pending.size,
      });
    }
    if (url.pathname.endsWith("/mcp")) {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders() });
      }
      if (request.method !== "POST") {
        return json({ error: "method_not_allowed" }, 405, { Allow: "POST, OPTIONS" });
      }
      return this.forwardMcp(request);
    }
    return json({ error: "not_found" }, 404);
  }

  async connectNode(request) {
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return json({ error: "websocket_upgrade_required" }, 426);
    }

    const secret = request.headers.get("X-PickPico-Relay-Secret")
      || request.headers.get("X-MCPocket-Relay-Secret")
      || "";
    if (secret.length < 24) {
      return json({ error: "relay_secret_required" }, 401);
    }

    const incomingHash = await sha256(secret);
    const storedHash = await this.ctx.storage.get("relaySecretHash");
    if (!storedHash) {
      await this.ctx.storage.put("relaySecretHash", incomingHash);
    } else if (!constantTimeEqual(storedHash, incomingHash)) {
      return json({ error: "relay_secret_rejected" }, 403);
    }

    for (const existing of this.nodeSockets()) {
      try {
        existing.close(1012, "Replaced by a newer PickPico connection");
      } catch (_) {
      }
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, ["node"]);
    server.serializeAttachment({
      role: "node",
      connectedAt: new Date().toISOString(),
      lastHeartbeatAt: Date.now(),
    });
    return new Response(null, { status: 101, webSocket: client });
  }

  async forwardMcp(request) {
    const sockets = this.nodeSockets();
    // Heartbeat is advisory health telemetry. Delivery ACK is the authoritative
    // end-to-end proof that the phone actually received this MCP request.
    const socket = sockets[0];
    if (!socket) {
      return json({ error: "node_offline" }, 503, corsHeaders());
    }

    const body = await request.text();
    const requestId = crypto.randomUUID();
    const headers = {};
    for (const name of [
      "authorization",
      "content-type",
      "accept",
      "mcp-protocol-version",
      "mcp-method",
      "mcp-name",
    ]) {
      const value = request.headers.get(name);
      if (value) headers[name] = value;
    }
    if (new URL(request.url).pathname.startsWith("/v3/")) {
      headers["x-pickpico-tool-profile"] = "thin-v1";
    }

    const responsePromise = new Promise((resolve) => {
      const timeout = setTimeout(() => {
        this.pending.delete(requestId);
        resolve({
          status: 504,
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ error: "node_timeout" }),
        });
      }, REQUEST_TIMEOUT_MS);
      const ackTimer = setTimeout(() => {
        const pending = this.pending.get(requestId);
        if (!pending || pending.acked) return;
        clearTimeout(pending.timeout);
        this.pending.delete(requestId);
        pending.resolve({
          status: 503,
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ error: "node_delivery_timeout" }),
        });
        try {
          socket.close(1012, "PickPico request delivery ACK timed out");
        } catch (_) {
        }
      }, DELIVERY_ACK_TIMEOUT_MS);
      this.pending.set(requestId, { resolve, timeout, ackTimer, acked: false, socket });
    });

    try {
      socket.send(JSON.stringify({
        type: "request",
        requestId,
        method: "POST",
        headers,
        body,
      }));
    } catch (_) {
      const pending = this.pending.get(requestId);
      if (pending) {
        clearTimeout(pending.timeout);
        if (pending.ackTimer) clearTimeout(pending.ackTimer);
      }
      this.pending.delete(requestId);
      return json({ error: "node_disconnected" }, 503, corsHeaders());
    }

    const proxied = await responsePromise;
    const outgoingHeaders = new Headers(corsHeaders());
    for (const [name, value] of Object.entries(proxied.headers || {})) {
      if (typeof value === "string" && value) outgoingHeaders.set(name, value);
    }
    return new Response(proxied.body || "", {
      status: Number(proxied.status) || 502,
      headers: outgoingHeaders,
    });
  }

  webSocketMessage(ws, message) {
    if (typeof message !== "string") return;
    let payload;
    try {
      payload = JSON.parse(message);
    } catch (_) {
      return;
    }
    if (payload?.type === "ping" && typeof payload.nonce === "string" && payload.nonce) {
      const attachment = ws.deserializeAttachment?.() || {};
      ws.serializeAttachment({
        ...attachment,
        role: "node",
        lastHeartbeatAt: Date.now(),
      });
      try {
        ws.send(JSON.stringify({ type: "pong", nonce: payload.nonce, serverTime: Date.now() }));
      } catch (_) {
      }
      return;
    }
    if (payload?.type === "request_ack" && typeof payload.requestId === "string") {
      const pending = this.pending.get(payload.requestId);
      if (!pending || pending.socket !== ws) return;
      pending.acked = true;
      if (pending.ackTimer) {
        clearTimeout(pending.ackTimer);
        pending.ackTimer = null;
      }
      const attachment = ws.deserializeAttachment?.() || {};
      ws.serializeAttachment({
        ...attachment,
        role: "node",
        lastHeartbeatAt: Date.now(),
      });
      return;
    }
    if (payload?.type !== "response" || typeof payload.requestId !== "string") return;

    const pending = this.pending.get(payload.requestId);
    if (!pending) return;
    clearTimeout(pending.timeout);
    if (pending.ackTimer) clearTimeout(pending.ackTimer);
    this.pending.delete(payload.requestId);
    const attachment = ws.deserializeAttachment?.() || {};
    ws.serializeAttachment({
      ...attachment,
      role: "node",
      lastHeartbeatAt: Date.now(),
    });
    pending.resolve({
      status: payload.status,
      headers: payload.headers || {},
      body: payload.body || "",
    });
  }

  webSocketClose(ws, code, reason) {
    this.failPending("node_disconnected");
  }

  webSocketError(ws, error) {
    this.failPending("node_disconnected");
  }

  nodeSockets() {
    return this.ctx.getWebSockets("node");
  }

  socketHeartbeatAgeMs(socket) {
    const attachment = socket?.deserializeAttachment?.() || {};
    const lastHeartbeatAt = Number(attachment.lastHeartbeatAt);
    if (!Number.isFinite(lastHeartbeatAt) || lastHeartbeatAt <= 0) return Infinity;
    return Math.max(0, Date.now() - lastHeartbeatAt);
  }

  isSocketHealthy(socket) {
    return this.socketHeartbeatAgeMs(socket) <= HEARTBEAT_STALE_MS;
  }

  failPending(error) {
    for (const [requestId, pending] of this.pending.entries()) {
      clearTimeout(pending.timeout);
      if (pending.ackTimer) clearTimeout(pending.ackTimer);
      pending.resolve({
        status: 503,
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ error }),
      });
      this.pending.delete(requestId);
    }
  }
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name",
    "Access-Control-Expose-Headers": "MCP-Protocol-Version",
  };
}

function json(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      ...extraHeaders,
    },
  });
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
