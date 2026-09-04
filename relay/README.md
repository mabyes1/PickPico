# PickPico Relay：自行部署

**本專案不提供公共 Relay。** 請部署自己的服務，再將取得的基底網址填入手機的 Remote Access。

目前提供 Cloudflare Worker + Durable Object 版本。手機主動連線，Agent 的請求經 Relay 轉送到手機，不需要向外開放手機的網路埠。

## Cloudflare 部署準備

1. 準備自己的 Cloudflare 帳號與 Workers 使用環境。
2. 安裝 `relay/package.json` 的相依套件。
3. 複製 `wrangler.jsonc` 成 `wrangler.local.jsonc`。後者已排除 Git，適合保存自己的部署設定。
4. 在自己的帳號建立 KV namespace，將配置中的 `REPLACE_WITH_YOUR_KV_NAMESPACE_ID` 換成其 ID；綁定名稱保持 `UPDATE_KV`。
5. 依需要填寫自己的 `account_id`、Worker 名稱，保留 `NODE_RELAY` 綁定及 migration。
6. 登入自己的帳號，使用該配置部署：

```sh
cd relay
npm ci
npx wrangler login
npx wrangler deploy --config wrangler.local.jsonc
```

將部署結果的 HTTPS 基底網址填入 PickPico，儲存並重啟服務。請勿把本文件的範例網址直接當成可用服務。

先檢查 `/health`，再從 Agent 執行 `phone.status` 驗證完整連線。詳細步驟見 [連線指南](../docs/remote-access-setup.md)。

## 除了 Cloudflare

| 方式 | 需要自行處理什麼 | 現況 |
| --- | --- | --- |
| VPS 或自己的公開伺服器 | HTTPS、WebSocket、裝置驗證、請求轉送與程序維護 | 需要移植 Relay；目前沒有現成 Node.js／Docker 版本。 |
| 支援 WebSocket 的應用代管平台 | 部署可在該平台執行的伺服器，處理連線生命週期與儲存 | 目前的 Worker 程式不能原封不動部署。 |
| 其他雲端 WebSocket 服務 | 整合連線、訊息傳送、節點狀態與驗證 | 需要另外實作相容後端。 |

相容後端必須理解 PickPico 的 request／request_ack／response 與心跳封包。不是任意代理或網路隧道的網址都能直接填入使用。

## 部署者要知道的存取方式

目前新 Node ID 首次連線會綁定其提供的 Relay secret；沒有管理員核准流程。Agent 端持有完整 MCP 網址即可呼叫，沒有另設帳號登入。

自行部署不等於內建白名單或完整限流。依服務用途管理網址、監看用量；機制與限制見 [遠端傳輸與安全設計](../docs/remote-transport.md)。

## 更新來源

`UPDATE_KV` 存放更新資訊與 APK 分塊，並提供 `/v1/update/latest` 與 `/v1/update/files/...`。未發布資料時，相關更新端點可能回 404，不影響它作為手機指令 Relay 的用途。

維護者發布更新時必須明確提供自己的網址：

```powershell
pwsh scripts/publish-update.ps1 -RelayBaseUrl 'https://your-relay.example.com'
```

腳本優先使用本機的 `relay/wrangler.local.jsonc`，也可以用 `-WranglerConfig` 指定配置。`PICKPICO_RELAY_BASE_URL` 環境變數可取代網址參數；未提供時腳本會停止，不會猜測或使用專案私人的服務。

APK 必須由相容的簽章簽署，手機仍會核對雜湊、套件名稱、簽章與版本。
