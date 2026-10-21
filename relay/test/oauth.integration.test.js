import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import test from "node:test";
import { build } from "esbuild";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

const nodeId = "oauth-test-phone-0001";
const origin = "http://localhost";
const resource = `${origin}/v4/nodes/${nodeId}/mcp`;
const issuer = `${origin}/oauth/${nodeId}`;
const verifier = randomBytes(32).toString("base64url");
const challenge = createHash("sha256").update(verifier).digest("base64url");
const bundle = await build({ entryPoints: [new URL("../src/index.js", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")],
  bundle: true, write: false, format: "esm", platform: "neutral", mainFields: ["module", "main"], external: ["cloudflare:workers"] });

async function callbackTarget(response) {
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Location"), null, "a form POST must finish on Relay, not redirect across origins");
  assert.match(response.headers.get("Content-Security-Policy"), /form-action 'self';/);
  assert.equal(response.headers.get("Referrer-Policy"), "no-referrer");
  const html = await response.text();
  assert.match(html, /location\.replace/);
  const href = /id="oauth-return" href="([^"]+)"/.exec(html)?.[1];
  assert.ok(href, html);
  return new URL(href.replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n))));
}

async function setup(t) {
  const mf = new Miniflare(convertV4MiniflareOptions({ modules: true, script: bundle.outputFiles[0].text,
    compatibilityDate: "2026-09-01", compatibilityFlags: ["global_fetch_strictly_public"],
    kvNamespaces: ["OAUTH_KV", "UPDATE_KV"], durableObjects: { NODE_RELAY: { className: "NodeRelay", useSQLite: true } },
    unsafeEvalBinding: undefined,
  }));
  t.after(() => mf.dispose());
  const states = new Map();
  const calls = [];
  const connected = await mf.dispatchFetch(`${origin}/v1/nodes/${nodeId}/connect`, {
    headers: { Upgrade: "websocket", "X-PickPico-Relay-Secret": "a".repeat(64) },
  });
  assert.equal(connected.status, 101);
  const ws = connected.webSocket;
  ws.accept();
  ws.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.type !== "request") return;
    ws.send(JSON.stringify({ type: "request_ack", requestId: message.requestId }));
    const rpc = JSON.parse(message.body);
    calls.push({ rpc, headers: message.headers });
    let result;
    if (rpc.params?.name === "human_help") {
      const id = `hh-${crypto.randomUUID()}`;
      result = { requestId: id, status: "waiting_human" };
      states.set(id, result);
    } else if (rpc.params?.name === "human_help_status") result = states.get(rpc.params.arguments.requestId);
    else result = { device: "test phone", battery: 72 };
    ws.send(JSON.stringify({ type: "response", requestId: message.requestId, status: 200,
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: rpc.id, result: { content: [{ type: "text", text: JSON.stringify(result) }], structuredContent: result } }),
    }));
  });
  const jar = new Map();
  async function fetch(url, options = {}) {
    const headers = new Headers(options.headers);
    if (jar.size) headers.set("Cookie", [...jar].map(([k, v]) => `${k}=${v}`).join("; "));
    if (options.method === "POST" && !url.endsWith("/token") && !url.endsWith("/register")) headers.set("Origin", origin);
    const response = await mf.dispatchFetch(url, { ...options, headers, redirect: "manual" });
    for (const value of response.headers.getSetCookie()) {
      const [name, ...rest] = value.split(";")[0].split("=");
      jar.set(name, rest.join("="));
    }
    return response;
  }
  const register = await fetch(`${issuer}/register`, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ client_name: '<test client "&>', redirect_uris: ["http://127.0.0.1:31000/callback"],
      grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], token_endpoint_auth_method: "none" }) });
  assert.equal(register.status, 201, await register.clone().text());
  const client = await register.json();
  async function begin(selectedClient = client) {
    const query = new URLSearchParams({ response_type: "code", client_id: selectedClient.client_id,
      redirect_uri: "http://127.0.0.1:31000/callback", scope: "phone offline_access", state: "state-123",
      code_challenge: challenge, code_challenge_method: "S256", resource });
    const page = await fetch(`${issuer}/authorize?${query}`);
    const html = await page.text();
    assert.ok(html.includes("&#60;test client"), html);
    const handle = /name="handle" value="([^"]+)"/.exec(html)?.[1];
    assert.ok(handle, html);
    const response = await fetch(`${issuer}/authorize`, { method: "POST", body: new URLSearchParams({ handle, decision: "approve" }) });
    assert.equal(response.status, 303);
    const waitingUrl = new URL(response.headers.get("Location"), origin).toString();
    const waitingResponse = await fetch(waitingUrl);
    assert.equal(waitingResponse.status, 200);
    const waiting = await waitingResponse.text();
    const tx = new RegExp(`/finish/([a-f0-9]{64})`).exec(waiting)?.[1];
    assert.ok(tx, waiting);
    const prompts = calls.filter(c => c.rpc.params?.name === "human_help").length;
    assert.equal((await fetch(waitingUrl)).status, 200);
    assert.equal(calls.filter(c => c.rpc.params?.name === "human_help").length, prompts, "reloading the waiting page must not prompt again");
    return tx;
  }
  async function approve(tx) {
    for (const [id] of states) states.set(id, { requestId: id, status: "completed", response: { action: "允許此連線" } });
    await delay(1600); // waiting-page GET may have just polled a pending request
    const r = await fetch(`${issuer}/check/${tx}`, { method: "POST" });
    assert.equal((await r.json()).status, "approved");
    const finish = await fetch(`${issuer}/finish/${tx}`, { method: "POST" });
    const location = await callbackTarget(finish);
    assert.equal(location.searchParams.get("state"), "state-123");
    assert.equal(location.searchParams.get("iss"), issuer);
    return location.searchParams.get("code");
  }
  async function token(params, selectedClient = client) {
    return fetch(`${issuer}/token`, { method: "POST", body: new URLSearchParams({ client_id: selectedClient.client_id, resource, ...params }) });
  }
  return { mf, fetch, states, calls, client, begin, approve, token, jar };
}

test("real Worker: discovery, physical-owner gate, refresh, node isolation, revocation and legacy closure", { timeout: 60000 }, async (t) => {
  const env = await setup(t);
  for (let v = 1; v <= 4; v++) {
    const response = await env.fetch(`${origin}/v${v}/nodes/${nodeId}/mcp`, { method: "POST", body: "{}", headers: { "X-PickPico-Verified-Grant": "a".repeat(64) } });
    assert.equal(response.status, 401);
    assert.match(response.headers.get("WWW-Authenticate"), /resource_metadata=/);
    const guidance = await response.json();
    assert.equal(guidance.error, "authorization_required");
    assert.equal(guidance.commandDispatched, false);
    assert.equal(guidance.onboarding.connection.url, `${origin}/v${v}/nodes/${nodeId}/mcp`);
    assert.equal(guidance.onboarding.recommended_mcp_url, resource);
    assert.equal(guidance.onboarding.authentication, "oauth");
    assert.equal(guidance.onboarding.phone_confirmation.initiated_by, "relay");
    assert.equal(guidance.onboarding.phone_confirmation.apk_reinstall_required, false);
    assert.match(response.headers.get("Link"), /rel="help"/);
  }
  assert.equal(env.calls.length, 0, "unauthorized calls must not reach the phone");
  assert.equal((await env.fetch(`${origin}/v1/nodes/${nodeId}/status`)).status, 401);
  assert.equal((await env.fetch(`${origin}/internal/oauth/active`, { method: "POST", body: "{}" })).status, 404);
  const metadata = await env.fetch(`${origin}/.well-known/oauth-protected-resource/v4/nodes/${nodeId}/mcp`);
  const resourceInfo = await metadata.json();
  assert.deepEqual(resourceInfo.authorization_servers, [issuer]);
  assert.equal(resourceInfo.resource_documentation, `${issuer}/guide?version=v4`);
  const guide = await env.fetch(resourceInfo.resource_documentation);
  assert.equal(guide.status, 200);
  const guideHtml = await guide.text();
  assert.match(guideHtml, /不需更新或重裝 APK/);
  assert.match(guideHtml, /human_help/);
  assert.ok(!guideHtml.includes('<form'), "reading instructions must not start consent");
  const machineGuide = await env.fetch(`${issuer}/guide?format=json&version=v3`);
  assert.equal((await machineGuide.json()).connection.url, resource.replace('/v4/', '/v3/'));
  const browserChallenge = await env.fetch(resource, { headers: { Accept: "text/html" } });
  assert.equal(browserChallenge.status, 401, "HTML instructions must retain the OAuth challenge status");
  assert.match(browserChallenge.headers.get("WWW-Authenticate"), /resource_metadata=/);
  assert.match(await browserChallenge.text(), /身分驗證：OAuth/);
  assert.equal(env.calls.length, 0, "all public guide and challenge requests must leave the phone untouched");
  const discovery = await env.fetch(`${origin}/.well-known/oauth-authorization-server/oauth/${nodeId}`);
  const info = await discovery.json();
  assert.equal(info.issuer, issuer);
  assert.equal(info.token_endpoint, `${issuer}/token`);
  assert.ok(info.grant_types_supported.includes("refresh_token"));

  const tx = await env.begin();
  const earlyFinish = await env.fetch(`${issuer}/finish/${tx}`, { method: "POST" });
  assert.equal(earlyFinish.status, 200);
  assert.equal(earlyFinish.headers.get("Location"), null, "browser approval alone must not return a code");
  assert.match(await earlyFinish.text(), /等待手機確認/);
  const stolen = await env.mf.dispatchFetch(`${issuer}/check/${tx}`, { method: "POST", headers: { Origin: origin } });
  assert.equal(stolen.status, 403, "transaction URL alone must not grant browser ownership");
  assert.equal((await env.fetch(`${issuer}/finish/${tx}`)).status, 405, "GET must never consume phone approval or issue an authorization code");
  assert.equal((await env.mf.dispatchFetch(`${issuer}/finish/${tx}`, { method: "POST", headers: { Origin: origin } })).status, 403, "finish must retain browser cookie binding");
  const csrf = await env.mf.dispatchFetch(`${issuer}/finish/${tx}`, { method: "POST", headers: { Origin: "https://evil.example", Cookie: [...env.jar].map(([k,v])=>`${k}=${v}`).join("; ") } });
  assert.equal(csrf.status, 403);
  const code = await env.approve(tx);
  const issued = await env.token({ grant_type: "authorization_code", code, code_verifier: verifier, redirect_uri: "http://127.0.0.1:31000/callback" });
  assert.equal(issued.status, 200, await issued.clone().text());
  const first = await issued.json();
  assert.ok(first.access_token && first.refresh_token);
  assert.equal(first.expires_in, 3600);
  const call = (access = first.access_token) => env.fetch(resource, { method: "POST", headers: { Authorization: `Bearer ${access}` },
    body: JSON.stringify({ jsonrpc: "2.0", id: 55, method: "tools/call", params: { name: "phone_status", arguments: { agent: "test" } } }) });
  const firstCall = await call();
  assert.equal(firstCall.status, 200, await firstCall.clone().text());
  assert.equal(env.calls.at(-1).headers.authorization, undefined, "OAuth bearer must never leak into the phone transport");
  assert.equal((await env.fetch(`${origin}/v4/nodes/different-phone-0001/mcp`, { method: "POST", headers: { Authorization: `Bearer ${first.access_token}` }, body: "{}" })).status, 401);
  assert.equal((await env.fetch(resource.replace('/v4/', '/v3/'), { method: "POST", headers: { Authorization: `Bearer ${first.access_token}` }, body: "{}" })).status, 401);
  const kv = await env.mf.getKVNamespace("OAUTH_KV");
  const tokenRecords = await kv.list({ prefix: "token:" });
  assert.equal(tokenRecords.keys.length, 1);
  const tokenRecord = await kv.get(tokenRecords.keys[0].name, "json");
  await kv.put(tokenRecords.keys[0].name, JSON.stringify({ ...tokenRecord, expiresAt: Math.floor(Date.now() / 1000) - 1 }));
  const expiredCall = await call();
  assert.equal(expiredCall.status, 401, "expired access token must be rejected");
  assert.equal((await expiredCall.json()).next_action, "refresh_or_reauthorize", "expiry guidance must prefer automatic refresh over another phone prompt");
  const grantRecords = await kv.list({ prefix: "grant:" });
  assert.equal(grantRecords.keys.length, 1);
  const originalGrant = await kv.get(grantRecords.keys[0].name, "json");
  // Stay clear of KV's 60-second minimum: crossing a clock tick at exactly
  // that boundary would test expiry rejection instead of sliding renewal.
  await kv.put(grantRecords.keys[0].name, JSON.stringify({ ...originalGrant, expiresAt: Math.floor(Date.now() / 1000) + 600 }));
  const promptsBefore = env.calls.filter((c) => c.rpc.params.name === "human_help").length;
  const refreshed = await env.token({ grant_type: "refresh_token", refresh_token: first.refresh_token });
  assert.equal(refreshed.status, 200, await refreshed.clone().text());
  const second = await refreshed.json();
  assert.notEqual(second.access_token, first.access_token);
  assert.notEqual(second.refresh_token, first.refresh_token);
  const renewedGrant = await kv.get(grantRecords.keys[0].name, "json");
  assert.ok(renewedGrant.expiresAt > Date.now() / 1000 + 170 * 86400, "refresh must slide the idle expiry instead of forcing a fixed re-login");
  assert.equal(env.calls.filter((c) => c.rpc.params.name === "human_help").length, promptsBefore, "refresh must never ask the phone again");
  assert.equal((await env.fetch(resource, { method: "POST", headers: { Authorization: `Bearer ${second.access_token}` }, body: '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' })).status, 200);

  const otherRegistered = await env.fetch(`${issuer}/register`, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ client_name: '<test client "&>', redirect_uris: ["http://127.0.0.1:31000/callback"], token_endpoint_auth_method: "none" }) });
  const otherClient = await otherRegistered.json();
  const otherCode = await env.approve(await env.begin(otherClient));
  const otherIssued = await env.token({ grant_type: "authorization_code", code: otherCode, code_verifier: verifier, redirect_uri: "http://127.0.0.1:31000/callback" }, otherClient);
  assert.equal(otherIssued.status, 200, await otherIssued.clone().text());
  const otherTokens = await otherIssued.json();

  // Management requires a new phone-owner approval, then revokes one specific grant.
  const manageHtml = await (await env.fetch(`${issuer}/manage`)).text();
  const manageCsrf = /name="csrf" value="([^"]+)"/.exec(manageHtml)[1];
  const managementStart = await env.fetch(`${issuer}/manage`, { method: "POST", body: new URLSearchParams({ csrf: manageCsrf }) });
  assert.equal(managementStart.status, 303);
  const waiting = await (await env.fetch(new URL(managementStart.headers.get("Location"), origin))).text();
  const managementId = /\/finish\/([a-f0-9]{64})/.exec(waiting)[1];
  for (const [id] of env.states) env.states.set(id, { requestId: id, status: "completed", response: { action: "允許此連線" } });
  await delay(1600);
  assert.equal((await (await env.fetch(`${issuer}/check/${managementId}`, { method: "POST" })).json()).status, "approved");
  const panel = await (await env.fetch(`${issuer}/finish/${managementId}`, { method: "POST" })).text();
  const grantKey = /name="grantKey" value="([a-f0-9]{64})"/.exec(panel)?.[1];
  assert.ok(grantKey, panel);
  const revoked = await env.fetch(`${issuer}/revoke/${managementId}`, { method: "POST", body: new URLSearchParams({ grantKey }) });
  assert.equal(revoked.status, 200);
  assert.equal((await call(second.access_token)).status, 401);
  assert.equal((await call(otherTokens.access_token)).status, 200, "revoking one connection must preserve another client's access");
  assert.equal((await env.token({ grant_type: "refresh_token", refresh_token: second.refresh_token })).status, 400);
  assert.equal((await env.token({ grant_type: "authorization_code", code, code_verifier: verifier, redirect_uri: "http://127.0.0.1:31000/callback" })).status, 400);
});

test("phone denial and invalid PKCE cannot issue access", { timeout: 30000 }, async (t) => {
  const env = await setup(t);
  const bad = new URL(`${issuer}/authorize`);
  bad.search = new URLSearchParams({ response_type: "code", client_id: env.client.client_id, redirect_uri: "http://127.0.0.1:31000/callback", resource });
  const denied = await env.fetch(bad);
  assert.ok(!(await denied.text()).includes('name="handle"'));
  const tx = await env.begin();
  for (const [id] of env.states) env.states.set(id, { requestId: id, status: "completed", response: { action: "拒絕" } });
  await delay(1600);
  assert.equal((await (await env.fetch(`${issuer}/check/${tx}`, { method: "POST" })).json()).status, "denied");
  const finish = await env.fetch(`${issuer}/finish/${tx}`, { method: "POST" });
  assert.equal((await callbackTarget(finish)).searchParams.get("error"), "access_denied");
});

test("browser consent refusal also ends POST locally before navigating to client", async (t) => {
  const env = await setup(t);
  const query = new URLSearchParams({ response_type: "code", client_id: env.client.client_id,
    redirect_uri: "http://127.0.0.1:31000/callback", scope: "phone offline_access", state: "denial-state",
    code_challenge: challenge, code_challenge_method: "S256", resource });
  const html = await (await env.fetch(`${issuer}/authorize?${query}`)).text();
  const handle = /name="handle" value="([^"]+)"/.exec(html)[1];
  const response = await env.fetch(`${issuer}/authorize`, { method: "POST", body: new URLSearchParams({ handle, decision: "deny" }) });
  const callback = await callbackTarget(response);
  assert.equal(callback.searchParams.get("error"), "access_denied");
  assert.equal(callback.searchParams.get("state"), "denial-state");
  assert.equal(env.calls.length, 0);
});
