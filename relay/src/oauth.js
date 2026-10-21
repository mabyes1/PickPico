import { OAuthAuthorizationServer, OAuthResourceServer, OAuthError, AuthorizationError, CimdFetchError } from "@cloudflare/workers-oauth-provider";
import { GRANT_HEADER, hash, randomSecret } from "./oauth-node.js";
import { consentPage, escapeHtml, managementPage, page, waitingPage } from "./oauth-pages.js";
import { connectionGuide, connectionGuidePage, oauthOnboardingResponse } from "./oauth-onboarding.js";

const NODE = "([A-Za-z0-9_-]{16,128})";
const protectedPath = new RegExp(`^/(v[1-4])/nodes/${NODE}/(mcp|status)$`);
const metadataPath = new RegExp(`^/\\.well-known/oauth-protected-resource/(v[1-4])/nodes/${NODE}/mcp$`);
const authPath = new RegExp(`^/oauth/${NODE}(?:/(.*))?$`);
const discoveryPath = new RegExp(`^/\\.well-known/(?:oauth-authorization-server|openid-configuration)/oauth/${NODE}$`);
const IDLE_SECONDS = 180 * 24 * 3600;

function service(env, nodeId) {
  return env.NODE_RELAY.get(env.NODE_RELAY.idFromName(nodeId));
}

async function internal(env, nodeId, operation, body) {
  const response = await service(env, nodeId).fetch(new Request(`https://internal/internal/oauth/${operation}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }));
  return { response, data: await response.json() };
}

export function authorizationServer(origin, nodeId) {
  const issuer = `${origin}/oauth/${nodeId}`;
  return new OAuthAuthorizationServer({
    issuer, authorizeEndpoint: `${issuer}/authorize`, tokenEndpoint: `${issuer}/token`,
    clientRegistrationEndpoint: `${issuer}/register`,
    resources: [1, 2, 3, 4].map((v) => `${origin}/v${v}/nodes/${nodeId}/mcp`),
    defaultResource: `${origin}/v4/nodes/${nodeId}/mcp`,
    scopesSupported: ["phone", "offline_access"], clientIdMetadataDocumentEnabled: true,
    accessTokenTTL: 3600, refreshTokenTTL: IDLE_SECONDS, refreshTokenIdleTTL: IDLE_SECONDS,
    clientRegistrationTTL: undefined,
    tokenExchangeCallback: async ({ env, props }) => {
      const { data } = await internal(env, nodeId, "active", { grantKey: props?.grantKey });
      if (props?.nodeId !== nodeId || data.active !== true) {
        throw new OAuthError("invalid_grant", { description: "這個手機連線已撤銷，請重新授權。" });
      }
    },
  });
}

function resourceServer(origin, version, nodeId, as, forward) {
  return new OAuthResourceServer({
    resourceMetadata: {
      resource: `${origin}/${version}/nodes/${nodeId}/mcp`,
      authorization_servers: [`${origin}/oauth/${nodeId}`], resource_name: "PickPico 手機",
    },
    requiredScopes: ["phone"],
    validateToken: (env) => (resource, token) => as.validateToken(resource, token, env),
    handler: { async fetch(request, env, ctx) {
      if (ctx.props?.nodeId !== nodeId || !ctx.auth.scope.includes("phone")) {
        return Response.json({ error: "access_denied" }, { status: 403 });
      }
      // Never trust or forward a caller-supplied grant marker or OAuth token.
      const headers = new Headers(request.headers);
      headers.delete("Authorization");
      headers.set(GRANT_HEADER, ctx.props.grantKey);
      return forward(new Request(request, { headers }), env);
    } },
  });
}

function cookieName(id) { return `__Host-pico-${id}`; }
function readCookie(request, name) {
  return (request.headers.get("Cookie") || "").split(";").map((part) => part.trim())
    .find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1) || "";
}
function cookie(name, value) { return `${name}=${value}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=300`; }
function sameOrigin(request) { return request.headers.get("Origin") === new URL(request.url).origin; }
function json(value, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store" } });
}

async function beginPhone(request, env, nodeId, body, headers = new Headers()) {
  const id = randomSecret();
  const browserSecret = randomSecret();
  const { response, data } = await internal(env, nodeId, "start", {
    ...body, id, browserHash: await hash(browserSecret),
  });
  if (!response.ok) return page("暫時無法開始授權", `<p>${escapeHtml(data.message)}</p>`, headers);
  headers.append("Set-Cookie", cookie(cookieName(id), browserSecret));
  // POST/redirect/GET lets the waiting page reload without consuming consent
  // again or displaying another phone prompt.
  headers.set("Location", `/oauth/${nodeId}/wait/${id}`);
  headers.set("Cache-Control", "no-store");
  return new Response(null, { status: 303, headers });
}

export async function oauthFetch(request, env, ctx, forward) {
  const url = new URL(request.url);
  const match = protectedPath.exec(url.pathname);
  const metadata = metadataPath.exec(url.pathname);
  const auth = authPath.exec(url.pathname);
  const discovery = discoveryPath.exec(url.pathname);
  if (!match && !metadata && !auth && !discovery) return null;
  if (url.protocol !== "https:" && !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
    return json({ error: "https_required" }, 400);
  }
  if (!env.OAUTH_KV) return json({ error: "oauth_not_configured" }, 503);
  if (match || metadata) {
    const [version, nodeId] = (match || metadata).slice(1, 3);
    const as = authorizationServer(url.origin, nodeId);
    const resource = resourceServer(url.origin, version, nodeId, as, forward);
    const guide = connectionGuide(url.origin, nodeId, match?.[3] === "status" ? "v4" : version);
    if (match?.[3] === "status") {
      // Status uses the current v4 MCP audience, and goes through the same
      // node-grant check. It never becomes an unauthenticated bypass.
      if (request.method === "OPTIONS") return new Response(null, { status: 204 });
      const token = /^Bearer ([^\s]+)$/i.exec(request.headers.get("Authorization") || "")?.[1];
      const valid = token && await as.validateToken(`${url.origin}/v4/nodes/${nodeId}/mcp`, token, env);
      if (!valid || valid.props?.nodeId !== nodeId || !valid.scope.includes("phone")) {
        return oauthOnboardingResponse(request, new Response(null, { status: 401, headers: {
          "WWW-Authenticate": `Bearer resource_metadata="${url.origin}/.well-known/oauth-protected-resource/v4/nodes/${nodeId}/mcp", scope="phone"`,
          "Cache-Control": "no-store",
        } }), guide);
      }
      const headers = new Headers(request.headers);
      headers.delete("Authorization");
      headers.set(GRANT_HEADER, valid.props.grantKey);
      const response = await forward(new Request(request, { headers }), env);
      return response.status === 401 ? oauthOnboardingResponse(request, response, guide) : response;
    }
    const response = await resource.fetch(request, env, ctx);
    if (metadata && response.ok) {
      const data = await response.json();
      return json({ ...data, resource_documentation: guide.documentation_url });
    }
    return response.status === 401 ? oauthOnboardingResponse(request, response, guide) : response;
  }
  const nodeId = auth?.[1] || discovery[1];
  const route = auth?.[2] || "";
  if (route === "guide") {
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    const version = /^v[1-4]$/.test(url.searchParams.get("version") || "") ? url.searchParams.get("version") : "v4";
    const guide = connectionGuide(url.origin, nodeId, version);
    return url.searchParams.get("format") === "json" || (request.headers.get("Accept") || "").includes("application/json")
      ? json(guide) : connectionGuidePage(guide);
  }
  const as = authorizationServer(url.origin, nodeId);
  const oauth = as.getOAuthApi(env);
  try {
    if (route === "authorize" && request.method === "GET") {
      const parsed = await oauth.parseAuthRequest(request);
      if (parsed.codeChallengeMethod !== "S256" || !/^[A-Za-z0-9_-]{43}$/.test(parsed.codeChallenge || "")) {
        return page("無法授權", "<p>客戶端必須使用 PKCE S256，請更新客戶端後重試。</p>");
      }
      const details = await oauth.describeConsent(parsed);
      const consent = await oauth.beginConsent(parsed);
      return consentPage(details, consent.handle, consent.headers);
    }
    if (route === "authorize" && request.method === "POST") {
      if (!sameOrigin(request)) return json({ error: "invalid_origin" }, 403);
      const form = await request.formData();
      const handle = String(form.get("handle") || "");
      if (form.get("decision") !== "approve") {
        const denied = await oauth.denyConsent(request, handle);
        return new Response(null, { status: 302, headers: denied.headers });
      }
      const approved = await oauth.approveConsent(request, handle, { scope: ["phone", "offline_access"] });
      const details = await oauth.describeConsent(approved.request);
      return beginPhone(request, env, nodeId, { authRequest: approved.request, details }, approved.headers);
    }
    if (route === "manage" && request.method === "GET") {
      const csrf = randomSecret();
      return page("管理手機連線", `<p>先在手機確認一次，即可查看及撤銷已授權的客戶端。</p><form method="post"><input type="hidden" name="csrf" value="${csrf}"><button>在手機確認管理權限</button></form>`, {
        "Set-Cookie": cookie("__Host-pico-management", csrf),
      });
    }
    if (route === "manage" && request.method === "POST") {
      if (!sameOrigin(request)) return json({ error: "invalid_origin" }, 403);
      const form = await request.formData();
      const csrf = readCookie(request, "__Host-pico-management");
      if (!/^[a-f0-9]{64}$/.test(csrf) || form.get("csrf") !== csrf) return json({ error: "invalid_csrf" }, 403);
      return beginPhone(request, env, nodeId, { kind: "manage", details: { clientName: "連線管理" } });
    }
    const txRoute = /^(wait|check|finish|revoke)\/([a-f0-9]{64})$/.exec(route);
    if (txRoute) {
      const [, operation, id] = txRoute;
      if (request.method !== (operation === "wait" ? "GET" : "POST")) return json({ error: "method_not_allowed" }, 405);
      if (operation !== "wait" && !sameOrigin(request)) return json({ error: "invalid_origin" }, 403);
      const secret = readCookie(request, cookieName(id));
      if (!/^[a-f0-9]{64}$/.test(secret)) return json({ error: "invalid_browser" }, 403);
      const body = { id, browserHash: await hash(secret) };
      if (operation === "wait") {
        const { response, data } = await internal(env, nodeId, "check", body);
        if (!response.ok) return json(data, response.status);
        // Only the provider-validated callback origin is allowed through CSP.
        return waitingPage(`/oauth/${nodeId}`, id, data.code, new Headers(), data.redirectOrigin);
      }
      if (operation === "check") {
        const { response, data } = await internal(env, nodeId, "check", body);
        return json(data, response.status);
      }
      if (operation === "revoke") {
        const form = await request.formData();
        const grantKey = String(form.get("grantKey") || "");
        if (!/^[a-f0-9]{64}$/.test(grantKey)) return json({ error: "invalid_grant" }, 400);
        const result = await internal(env, nodeId, "revoke", { ...body, grantKey });
        if (!result.response.ok) return json(result.data, result.response.status);
        const { data } = await internal(env, nodeId, "grants", body);
        return managementPage(`/oauth/${nodeId}`, id, data.grants || []);
      }
      const state = await internal(env, nodeId, "check", body);
      if (!state.response.ok) return json(state.data, state.response.status);
      if (state.data.kind === "manage") {
        const { response, data } = await internal(env, nodeId, "grants", body);
        if (!response.ok) return json(data, response.status);
        return managementPage(`/oauth/${nodeId}`, id, data.grants);
      }
      if (state.data.status === "pending") {
        return waitingPage(`/oauth/${nodeId}`, id, state.data.code, new Headers(), state.data.redirectOrigin);
      }
      const claimed = await internal(env, nodeId, "claim", body);
      if (!claimed.response.ok) return json(claimed.data, claimed.response.status);
      if (claimed.data.redirectTo) return Response.redirect(claimed.data.redirectTo, 302);
      if (claimed.data.denied) {
        const target = new URL(claimed.data.request.redirectUri);
        target.searchParams.set("error", "access_denied");
        target.searchParams.set("state", claimed.data.request.state);
        target.searchParams.set("iss", claimed.data.request.issuer);
        return Response.redirect(target.toString(), 302);
      }
      const result = await oauth.completeAuthorization({
        request: claimed.data.request, userId: nodeId, metadata: { clientName: claimed.data.details.clientName },
        scope: ["phone", "offline_access"], props: { nodeId, grantKey: claimed.data.grantKey },
        revokeExistingGrants: false,
      });
      const committed = await internal(env, nodeId, "commit", { ...body, grantKey: claimed.data.grantKey, redirectTo: result.redirectTo });
      if (!committed.response.ok) return json({ error: "authorization_commit_failed" }, 503);
      return new Response(null, { status: 302, headers: { Location: result.redirectTo, "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" } });
    }
    return as.fetch(request, env, ctx);
  } catch (error) {
    if (error instanceof AuthorizationError && error.redirectTo) return Response.redirect(error.redirectTo, 302);
    if (error instanceof AuthorizationError || error instanceof CimdFetchError) {
      return page("授權未完成", `<p>${escapeHtml(error.description || "無法驗證客戶端，請回客戶端重新開始連線。")}</p>`);
    }
    // Do not log request URLs, codes, cookies, or tokens.
    console.error("PickPico OAuth failed", error?.name || "Error");
    return json({ error: "oauth_unavailable", message: "授權服務暫時無法使用，請稍後重試。" }, 503);
  }
}
