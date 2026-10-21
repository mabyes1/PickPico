# Relay OAuth：首次手機確認，日常自動續期

2026-10-20。僅修改 Cloudflare Relay；Android APK、套件、簽章、Local Bearer、Node ID、Relay secret、ADB 配對及能力執行器保持原樣。

## 使用方式

在 MCP Client 使用手機顯示的 `/v4/nodes/<node-id>/mcp` URL，驗證方式選 OAuth。舊 APK 複製內容仍可能標示 `authentication: none`，請只取 URL，依客戶端介面選 OAuth。

首次連結：瀏覽器顯示客戶端、目的地及授權範圍，按繼續；手機 HUMAN HELP 顯示相同六位確認碼，手機持有人按「允許此連線」；瀏覽器自動返回 Client。此流程不要求搬移長 token。

Access token 有效一小時。Refresh token 每次使用輪換；成功續期會延長為其後 180 天，持續正常使用不需反覆登入。憑證被撤銷、閒置超過 180 天或 Client 遺失憑證時，才需重新連結。Client 也必須正確保存及更新 token。

管理已授權連線：開啟 `https://<relay>/oauth/<node-id>/manage`，在手機確認管理權限後，逐一撤銷。每次管理確認有效五分鐘。撤銷由 Durable Object 強一致狀態立即阻止新請求及續期，不會中斷已開始的手機指令，也不影響其他客戶端。

## 安全邊界

### 給一般使用者與 AI 的首次連線引導

將手機自己的 MCP 網址加入支援 MCP OAuth 的客戶端。Relay 對未授權呼叫保留 401／`WWW-Authenticate`，JSON body 補上 `onboarding`：驗證方式、精確 MCP resource、建議新連線使用的 v4 URL、protected resource／AS metadata、`phone offline_access`、使用者說明及 AI 操作步驟。舊版本網址的 challenge 仍指向該版本 resource，避免在授權途中混用不同版本的 audience。

Protected resource metadata 另有標準 `resource_documentation` 連結，指向 `/oauth/<node-id>/guide?version=v4`。這個公開頁面提供中文步驟及 JSON 連結；`format=json` 或 `Accept: application/json` 回傳機器可讀說明。瀏覽器直接開啟 MCP 網址時會看到同樣中文引導，HTTP 狀態仍為 401。

AI 引導客戶端開始 OAuth，讓手機持有人核對六位數並允許，授權完成後接回原任務。Relay 才能發出這筆限縮的 `human_help`；讀取 guide／metadata／401 不觸發手機、不產生 grant。步驟也區分憑證到期、手機離線及不支援 OAuth 的客戶端，避免叫使用者搬移 token、反覆確認、重裝 APK 或重設配對。實際顯示授權 UI、保存憑證與接回任務，仍依客戶端支援程度。

- 採 Cloudflare 官方 `@cloudflare/workers-oauth-provider` 1.2.3，PKCE S256、嚴格 redirect URI、一次性 code、client metadata documents 與相容 DCR。
- 每支手機有獨立 AS issuer `/oauth/<node-id>`；所有 v1～v4 MCP 路徑及 status 都驗證 token。Token 綁定手機與特定版本的 MCP resource，跨手機／跨 resource 無效。
- 瀏覽器確認受 provider 的 HttpOnly cookie、一次性 handle、Origin 檢查及 CSP 保護。手機確認另綁定同一瀏覽器的隨機憑證，僅有交易 URL 無效。
- 未授權流量只能觸發受限的手機確認與其狀態查詢；工具名稱、參數由 Relay 建立。一般 MCP 請求先通過 OAuth 及手機 grant 檢查。
- 一支手機同時只顯示一筆確認，十分鐘最多三筆；逾期交易自動清除。一般工具流量仍沿用既有限制，這不是完整 DDoS 防護。
- OAuth token 不送進手機，Relay 也不記錄 token、code 或 cookie。`OAUTH_KV` 與更新檔的 `UPDATE_KV` 分離。
- 手機 WebSocket 仍依原有 Relay secret 驗證，不因 access token 到期重建連線。Ask Me／YOLO 與 Android 權限繼續由手機決定。

## 部署及回復

建立獨立 `OAUTH_KV` namespace，加到既有 `wrangler.local.jsonc`，保留 `NODE_RELAY` 和 `UPDATE_KV` 的 ID 與 migration。啟用 `global_fetch_strictly_public`，讓 CIMD 只取得公開網路的 HTTPS metadata。

先 `npm ci`、`npm test`、`wrangler deploy --dry-run --config wrangler.local.jsonc`，再部署。原本無驗證的 Client 會先收到 401，需要選 OAuth 重新連結一次。不要為了兼容而放行舊版 URL。

部署前記錄現有 Worker version。若新版本無法完成手機確認或正常呼叫，使用 `wrangler rollback <previous-version> --config wrangler.local.jsonc` 回復。OAuth 資料與舊版 NodeRelay 的配對資料使用不同 key，不需刪除 DO 或重置手機。

## 驗證

`relay/test/oauth.integration.test.js` 在真實 workerd／Miniflare 內執行 OAuth provider、KV、Durable Object 與 WebSocket 收送，手機端由測試對端模擬。涵蓋 metadata、PKCE、瀏覽器與手機雙重確認、拒絕、舊入口封鎖、token 續期不再詢問、跨手機隔離及撤銷。

這些是自動驗證。正式驗收另外需要真實 MCP Client、實體手機首次確認、`server_info`／`phone.status` 成功、續期後仍能呼叫，以及原手機版本和配對保持一致。部署後的實測紀錄應與本文件一同交付。

### 2026-10-21 驗收狀態

- workerd／Miniflare 自動驗證 9/9 通過，手機為模擬對端。涵蓋 v1～v4 未授權入口、HTML／JSON 引導、metadata、PKCE、瀏覽器與手機確認、等待頁重新整理不重發提示、續期輪換與滑動期限、跨 resource／手機隔離及撤銷。
- 真實 Chrome 與實體手機已完成首次確認，成功返回 ChatGPT，並透過 v4 OAuth 連線讀取 server_info、phone_status 與 dev(info)。手機沿用 0.17.18，未重裝 APK，配對保留。手機離線後恢復連線也能沿用既有授權。
- 正式站已檢查未帶 token 的 v3／v4 呼叫仍為 401，附 OAuth challenge 及引導；提供無效 token 時優先引導自動續期。公開說明不會啟動手機確認。
- Chrome 表單需要 same-origin Referrer Policy，以保留 Origin 檢查。等待頁 CSP 僅允許 provider 驗證過的 callback origin；POST/303/GET 避免重新整理重送 consent。
- 尚待個別使用者實際 AI 客戶端的首次連線體驗，以及正式 ChatGPT access token 到期後的自動續期驗收。自動測試通過不等同所有客戶端已實測。

每次部署的 Worker version、回復版本、客戶端識別碼及裝置診斷應另存私有部署紀錄，不放入公開儲存庫。
