export const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

export function page(title, body, extraHeaders = {}, script = "") {
  const nonce = crypto.randomUUID();
  const headers = new Headers(extraHeaders);
  for (const [name, value] of Object.entries({ "Content-Type": "text/html; charset=utf-8",
    // Chrome sends Origin: null on form POSTs from a no-referrer document.
    // Same-origin preserves our CSRF Origin check without leaking authorization
    // query parameters to an external destination.
    "Cache-Control": "no-store", "Referrer-Policy": "same-origin", "X-Frame-Options": "DENY",
    "Content-Security-Policy": `default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`,
    "X-Content-Type-Options": "nosniff" })) headers.set(name, value);
  return new Response(`<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)} · PickPico</title>
<style>body{margin:0;background:#101713;color:#e8f0e9;font:17px/1.6 system-ui,sans-serif}main{max-width:540px;margin:8vh auto;padding:28px}h1{font-size:28px}p{color:#bdcdc0}.card{background:#1b2720;padding:20px;border-radius:16px;overflow-wrap:anywhere}button{font:inherit;border:0;border-radius:10px;padding:13px 20px;margin:12px 8px 0 0;cursor:pointer;background:#b6f3be;color:#102215}.secondary{background:#304036;color:#fff}.code{font-size:38px;font-weight:700;letter-spacing:.16em;color:#c8f9cc}a{color:#b6f3be}small{color:#a9b9ae}button:disabled{opacity:.5}</style>
<main><small>PickPico · 安全連線</small><h1>${escapeHtml(title)}</h1>${body}</main>${script ? `<script nonce="${nonce}">${script}</script>` : ""}</html>`, {
    headers,
  });
}

// Only pass a callback already validated by the OAuth provider. Returning a
// document (rather than a POST -> 302) ends the form submission on this origin.
// The subsequent navigation and any client-side redirect chain are ordinary
// navigations, not cross-origin form actions. Keep the owner cookie/Origin
// checks on the POST; a transaction URL alone must never issue a code.
export function callbackPage(redirectTo, headers = {}) {
  const target = new URL(redirectTo);
  if (!["https:", "http:"].includes(target.protocol)) throw new Error("invalid_callback_protocol");
  const response = page("正在返回 AI 客戶端", `<p>這次授權流程已處理，正在返回客戶端。</p><p>若沒有自動跳轉，請點選下方連結。</p><a id="oauth-return" href="${escapeHtml(target.href)}" rel="noreferrer">返回 AI 客戶端</a>`, headers,
    `location.replace(document.getElementById('oauth-return').href);`);
  response.headers.delete("Location");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}

export function consentPage(details, handle, headers) {
  return page("允許這個客戶端連接手機？", `<div class="card"><strong>${escapeHtml(details.clientName)}</strong>
<p>${details.clientDomain ? `客戶端網域：${escapeHtml(details.clientDomain)}` : "客戶端名稱由對方提供，尚未驗證。"}</p>
<p>授權送往：<strong>${escapeHtml(details.redirectHost)}</strong>${details.redirectIsLoopback ? "（你電腦上的本機應用程式）" : ""}</p>
<p>可使用這支手機目前啟用的能力；手機上的能力開關與 Ask Me／YOLO 政策仍適用。</p></div>
<p>第一次需要你在手機確認，之後會自動續期，正常使用不需重複登入。</p>
<form method="post"><input type="hidden" name="handle" value="${escapeHtml(handle)}"><button name="decision" value="approve">繼續，並在手機確認</button><button class="secondary" name="decision" value="deny">拒絕</button></form>`, headers);
}

export function waitingPage(base, id, code, headers) {
  const check = JSON.stringify(`${base}/check/${id}`);
  const finish = escapeHtml(`${base}/finish/${id}`);
  return page("請在手機上確認", `<p>打開 PickPico，確認手機與這裡顯示相同的六位數，再選「允許此連線」。</p><div class="card"><div class="code">${escapeHtml(code)}</div><p id="status" role="status">等待手機確認…</p></div>
<p>如果你沒有主動開始這次連線，請在手機選擇拒絕。</p><form id="finish" method="post" action="${finish}"><button class="secondary">已在手機確認，繼續</button></form>`, headers,
    `let stopped=false;async function poll(){if(stopped)return;try{const r=await fetch(${check},{method:'POST'});const d=await r.json();if(!r.ok){stopped=true;document.getElementById('status').textContent=d.message||'授權已過期，請回客戶端重新連線。';return}if(['approved','done','denied'].includes(d.status)){stopped=true;document.getElementById('status').textContent='正在返回客戶端…';document.getElementById('finish').submit();return}}catch{document.getElementById('status').textContent='連線暫時中斷，正在重試…'}setTimeout(poll,2000)}poll();`);
}

export function managementPage(base, id, grants, headers) {
  return page("已授權的連線", `<p>撤銷後，這個客戶端的後續請求與續期都會被拒絕，其他連線繼續使用。</p>${grants.length ? grants.map((g) => `<div class="card"><strong>${escapeHtml(g.clientName)}</strong><p>${escapeHtml(g.redirectHost)} · ${g.active ? "使用中" : "已撤銷"}</p>${g.active ? `<form method="post" action="${escapeHtml(`${base}/revoke/${id}`)}"><input type="hidden" name="grantKey" value="${escapeHtml(g.grantKey)}"><button class="secondary">撤銷這個連線</button></form>` : ""}</div><br>`).join("") : "<p>目前沒有已授權的連線。</p>"}`, headers);
}
