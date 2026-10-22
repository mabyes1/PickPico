// All entry points in this module are Durable Object-internal. The public
// Worker never routes an incoming /internal URL to a node.
export const GRANT_HEADER = "X-PickPico-Verified-Grant";
export const ALLOW_ACTION = "允許此連線";
const TX_TTL = 5 * 60_000;
const json = (value, status = 200) => Response.json(value, { status });
const key = (id) => `oauth:transaction:${id}`;

export async function hash(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function grantActive(node, grantKey) {
  if (!/^[a-f0-9]{64}$/.test(grantKey || "")) return false;
  const grant = await node.ctx.storage.get(`oauth:grant:${grantKey}`);
  return grant?.active === true;
}

function rpcResult(response) {
  if (!response.ok) throw new Error(`phone_http_${response.status}`);
  return response.json().then((rpc) => {
    if (rpc.error || rpc.result?.isError) throw new Error("phone_request_failed");
    const result = rpc.result?.structuredContent
      || (rpc.result?.content?.find((item) => item.type === "text")?.text
        ? JSON.parse(rpc.result.content.find((item) => item.type === "text").text)
        : rpc.result);
    if (!result || result.isError || result.error) throw new Error("phone_unavailable");
    return result;
  });
}

async function phone(node, name, args) {
  // Exact allowlist: unauthenticated OAuth traffic can only display its own
  // confirmation or read that confirmation. It cannot invoke arbitrary tools.
  if (name !== "human_help" && name !== "human_help_status") throw new Error("invalid_owner_check");
  return rpcResult(await node.forwardMcp(new Request("https://internal/v4/nodes/owner/mcp", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: crypto.randomUUID(), method: "tools/call",
      params: { name, arguments: { ...args, agent: "PickPico OAuth relay" } } }),
  })));
}

async function loadTransaction(node, body) {
  if (!/^[a-f0-9]{64}$/.test(body.id || "")) return null;
  const tx = await node.ctx.storage.get(key(body.id));
  if (!tx || tx.browserHash !== body.browserHash || tx.expiresAt <= Date.now()) return null;
  return tx;
}

export async function handleNodeOAuth(node, request) {
  const path = new URL(request.url).pathname;
  if (!path.startsWith("/internal/oauth/")) return null;
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const body = await request.json();
  const operation = path.slice("/internal/oauth/".length);
  if (operation === "active") return json({ active: await grantActive(node, body.grantKey) });
  if (operation === "start") {
    if (!/^[a-f0-9]{64}$/.test(body.id || "") || !/^[a-f0-9]{64}$/.test(body.browserHash || "")) {
      return json({ error: "invalid_request" }, 400);
    }
    if (!node.nodeSockets().some((socket) => node.isSocketHealthy(socket))) {
      return json({ error: "phone_offline", message: "請打開 PickPico，等候 Remote Access 顯示 Connected，再重試。" }, 503);
    }
    // One confirmation at a time, at most three prompts in ten minutes per
    // phone. Reserving in a transaction also covers concurrent requests.
    const admitted = await node.ctx.storage.transaction(async (storage) => {
      const gate = await storage.get("oauth:promptGate") || { attempts: [] };
      const attempts = gate.attempts.filter((time) => time > Date.now() - 10 * 60_000);
      if (gate.busyUntil > Date.now() || attempts.length >= 3) return false;
      await storage.put("oauth:promptGate", { attempts: [...attempts, Date.now()], busyUntil: Date.now() + TX_TTL });
      return true;
    });
    if (!admitted) return json({ error: "confirmation_busy", message: "手機已有待確認的連線，或短時間內要求過多。請稍候再試。" }, 429);
    const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
    const tx = { id: body.id, browserHash: body.browserHash, request: body.authRequest || null,
      details: body.details, kind: body.kind === "manage" ? "manage" : "authorize", code,
      expiresAt: Date.now() + TX_TTL, status: "pending", lastPollAt: 0 };
    try {
      const isManagement = tx.kind === "manage";
      const result = await phone(node, "human_help", {
        wait: false, title: isManagement ? "管理 PickPico 已授權連線" : "核准新的 PickPico 連線",
        instruction: `確認碼：${code}\n${isManagement ? "允許這個瀏覽器管理及撤銷已授權連線。" : `客戶端：${body.details.clientName}\n授權送往：${body.details.redirectHost}${body.details.redirectIsLoopback ? "（你電腦上的本機應用程式）" : ""}\n${body.details.clientDomain ? `客戶端網域：${body.details.clientDomain}` : "客戶端名稱由對方自行填寫，未驗證。"}\n允許此客戶端使用這支手機；既有能力開關與 Ask Me／YOLO 政策仍適用。`}
\n只有你剛剛主動開始連線，且瀏覽器顯示相同確認碼時才允許。允許後平常會自動續期，不會每次登入。不認識的要求請拒絕。`,
        actions: [ALLOW_ACTION, "拒絕"], allowTextReply: false, allowImages: false,
        maxImages: 0, idleTimeoutSeconds: 180,
      });
      if (!/^hh-[a-f0-9-]+$/.test(result.requestId || "")) throw new Error("phone_confirmation_failed");
      tx.humanHelpId = result.requestId;
      await node.ctx.storage.put(key(tx.id), tx);
      await scheduleCleanup(node);
      return json({ status: "pending", code, expiresAt: tx.expiresAt });
    } catch {
      const gate = await node.ctx.storage.get("oauth:promptGate");
      if (gate) await node.ctx.storage.put("oauth:promptGate", { ...gate, busyUntil: 0 });
      return json({ error: "phone_confirmation_failed", message: "手機未能顯示確認要求，請確認 PickPico 已連線後重試。" }, 503);
    }
  }
  const tx = await loadTransaction(node, body);
  if (!tx) return json({ error: "confirmation_expired", message: "這次授權已過期，或不是由這個瀏覽器開始。請重新連線。" }, 403);
  if (operation === "check") {
    const mayPoll = await node.ctx.storage.transaction(async (storage) => {
      const latest = await storage.get(key(tx.id));
      if (latest.status !== "pending" || latest.lastPollAt >= Date.now() - 1500) return false;
      await storage.put(key(tx.id), { ...latest, lastPollAt: Date.now() });
      return true;
    });
    if (mayPoll) {
      let decision;
      try {
        const result = await phone(node, "human_help_status", { requestId: tx.humanHelpId, waitMs: 0, includeAttachmentData: false });
        if (result.status === "completed") {
          decision = result.response?.action === ALLOW_ACTION ? "approved" : "denied";
        } else if (result.status !== "waiting_human") decision = "denied";
      } catch { /* A temporary disconnect keeps this confirmation pending. */ }
      await node.ctx.storage.transaction(async (storage) => {
        const latest = await storage.get(key(tx.id));
        // A delayed status response must never reopen a consumed transaction.
        if (latest?.status === "pending" && decision) await storage.put(key(tx.id), { ...latest, status: decision });
      });
      if (decision) {
        const gate = await node.ctx.storage.get("oauth:promptGate");
        if (gate) await node.ctx.storage.put("oauth:promptGate", { ...gate, busyUntil: 0 });
      }
    }
    const current = await node.ctx.storage.get(key(tx.id));
    return json({ status: current.status, code: current.code, kind: current.kind,
      redirectOrigin: current.request?.redirectUri ? new URL(current.request.redirectUri).origin : "" });
  }
  if (operation === "claim") {
    return node.ctx.storage.transaction(async (storage) => {
      const latest = await storage.get(key(tx.id));
      if (latest.status === "done") return json({ redirectTo: latest.redirectTo });
      if (latest.status === "denied") return json({ denied: true, request: latest.request });
      if (latest.status !== "approved" || latest.kind !== "authorize") return json({ error: "not_approved" }, 403);
      latest.status = "issuing";
      latest.grantKey = randomSecret();
      await storage.put(key(tx.id), latest);
      return json({ request: latest.request, grantKey: latest.grantKey, details: latest.details });
    });
  }
  if (operation === "commit") {
    if (tx.status !== "issuing" || body.grantKey !== tx.grantKey) return json({ error: "invalid_confirmation" }, 403);
    await node.ctx.storage.put(`oauth:grant:${tx.grantKey}`, {
      active: true, clientName: tx.details.clientName, redirectHost: tx.details.redirectHost, createdAt: Date.now(),
    });
    await node.ctx.storage.put(key(tx.id), { ...tx, status: "done", redirectTo: body.redirectTo });
    return json({ ok: true });
  }
  if ((operation === "grants" || operation === "revoke") && tx.kind === "manage" && tx.status === "approved") {
    if (operation === "revoke") {
      const grant = await node.ctx.storage.get(`oauth:grant:${body.grantKey}`);
      if (!grant) return json({ error: "grant_not_found" }, 404);
      await node.ctx.storage.put(`oauth:grant:${body.grantKey}`, { ...grant, active: false, revokedAt: Date.now() });
      return json({ ok: true });
    }
    const grants = await node.ctx.storage.list({ prefix: "oauth:grant:" });
    return json({ grants: [...grants].map(([name, grant]) => ({ ...grant, grantKey: name.slice("oauth:grant:".length) }))
      .sort((a, b) => a.createdAt - b.createdAt) });
  }
  return json({ error: "access_denied" }, 403);
}

export function randomSecret() {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function scheduleCleanup(node) {
  if (!await node.ctx.storage.getAlarm()) await node.ctx.storage.setAlarm(Date.now() + TX_TTL);
}

export async function cleanupOAuth(node) {
  const transactions = await node.ctx.storage.list({ prefix: "oauth:transaction:" });
  let next = Infinity;
  for (const [name, tx] of transactions) {
    if (tx.expiresAt <= Date.now()) await node.ctx.storage.delete(name);
    else next = Math.min(next, tx.expiresAt);
  }
  if (Number.isFinite(next)) await node.ctx.storage.setAlarm(next);
}
