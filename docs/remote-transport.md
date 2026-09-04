# PickPico 遠端傳輸與安全設計

本文記錄 2026-09-04 儲存庫內的 Android 0.16.34 與 Relay 實作。程式中存在的機制，不代表所有裝置、網路或客戶端都已完成實測。

一般使用者請先看 [連線指南](remote-access-setup.md)。

## 請求實際怎麼走

```text
遠端 Agent / MCP 客戶端
    │ HTTPS POST
    ▼
Cloudflare Worker → 依 Node ID 對應的 Durable Object
    │ 透過手機已建立的 WebSocket 傳遞請求
    ▼
Android RelayClient
    │ HTTP POST + Local Bearer
    ▼
127.0.0.1:8765/mcp → McpProtocol → CommandRuntime → Android 能力
```

手機主動建立 WebSocket，不需要讓外部網路直接連入手機。使用專案的 HTTPS Relay 時，手機連線為 WSS。

**目前 RelayClient 仍透過手機內部的 HTTP loopback 呼叫 MCP server**，並未改成直接呼叫記憶體中的 dispatcher。回應也沿原路回傳，包含圖片、音訊的 MCP 內容。

Local MCP 另外監聽 `0.0.0.0:8765`，同時供區域網路與 loopback 使用。區域網路直連不經 Cloudflare。

## 路由與工具清單

| 路由 | 用途 |
| --- | --- |
| `GET /health` | Relay 本身的健康回應，不代表手機在線。 |
| `GET /v1/nodes/<node-id>/connect` | 手機的 WebSocket 升級入口，需要 Relay secret。 |
| `/v1/nodes/<node-id>/status` | 目前連線、心跳與等待請求數。持有 Node ID 即可查詢。 |
| `POST /v3/nodes/<node-id>/mcp` | 目前 App 產生的遠端 MCP 入口，使用精簡工具清單。 |
| `POST /v1/nodes/<node-id>/mcp`、`/v2/.../mcp` | 保留完整工具清單的相容入口。 |

`/mcp` 支援 POST，另接受 OPTIONS；這不是一般可直接在瀏覽器閱讀的網頁，也不是 GET SSE 訂閱端點。

對 `/v3` 請求，Relay 加入 `X-PickPico-Tool-Profile: thin-v1`，由手機選擇工具清單。版本路徑不會建立另一支手機或另一份能力執行器。

目前精簡清單為：

```text
server_info          capability_search    capability_status
policy_status        command_run          command_status
task_runtime_info    task_create          task_update
task_status
```

## 三種憑證，各自做什麼

| 資料 | 用途 | 存取邊界 |
| --- | --- | --- |
| Remote MCP 完整網址 | 讓 Agent 找到並呼叫這支手機 | 網址含隨機 Node ID；遠端呼叫沒有另外要求登入或 Bearer 驗證。 |
| Relay secret | 手機建立 WebSocket 時驗證身分 | 手機送出 secret；Durable Object 儲存 SHA-256 雜湊，比對後才接受後續連線。 |
| Local Bearer token | 保護手機上的 HTTP MCP 入口 | 區域網路客戶端要提供；RelayClient 在 loopback 請求中從手機設定讀取並加入。 |

Node ID 由 16 個隨機位元組產生；Relay secret 由 32 個隨機位元組產生，保存在手機設定中。Relay 對該 Node ID 首次收到合格 secret 時建立綁定，後續連線須相符。

Remote MCP 網址是持有即可使用的存取方式，**不是 OAuth，也沒有逐一識別 Agent 帳號**。更改 Local Bearer 不會撤銷已外流的 Remote MCP 網址。停用服務會中止可用性，但不等同輪換網址。

## 加密與資料經過哪裡

- 專案 Relay 基底網址為 `https://relay.pickpico.workers.dev`，Agent 到 Relay 使用 HTTPS，手機到 Relay 使用 WSS。
- Relay 會處理請求及回應內容；這不是 Agent 到手機、連 Relay 都無法讀取的端到端加密。
- Local 與 loopback 使用 HTTP。Local token 和內容可能被可觀察該網路流量的人讀到，因此直連應使用可信任網路。
- App 目前接受 HTTP 或 HTTPS Relay 基底網址。設定 HTTP 時不具有 HTTPS/WSS 的保護；文件不把程式描述成強制 HTTPS。
- Durable Object 持久儲存 Relay secret 雜湊；正在等待的請求存在記憶體 Map，WebSocket 附帶連線與心跳時間。程式未把 MCP 請求歷史寫入 KV，但這不等同對部署平台日誌或資料保留作保證。

## 心跳、等待與中斷

| 實作設定 | 目前值或行為 |
| --- | --- |
| 手機心跳間隔 | 10 秒 |
| 手機等待心跳回覆 | 15 秒，失敗後嘗試重連 |
| Relay 健康判定 | 心跳超過 35 秒不再視為健康連線 |
| 收件確認 | 手機收到封包即回 ACK，之後才排入執行；ACK 不代表已執行或完成。 |
| Relay 等待 ACK | 5 秒，未收到則回 `node_delivery_timeout` |
| Relay 等待完整回應 | 最長 30 分鐘，超過回 `node_timeout` |
| Android loopback HTTP 讀取等待 | 31 分鐘 |
| RelayClient 執行池 | 2 個工作執行緒 |
| Local HTTP 執行池 | 4 個工作執行緒 |

HUMAN HELP 有自己的可續時閒置期限；30 分鐘是 Relay 的傳輸等待上限，不能把它當成每項能力或客戶端都會等待的時間。

長時間執行的指令會占用工作執行緒，後續請求可能排隊。這版沒有為人工等待提供獨立執行池，也不能承諾大量並行時其他指令不受影響。

同一 Node ID 的新連線會取代舊連線。已斷線的請求可能失敗；程式沒有保證跨網路切換時自動重播或只執行一次。MCP 的取消通知目前被接收並回應，不會據此終止已開始的能力。客戶端逾時後，應先確認實際結果再決定是否重試。

## HTTP 與流量限制

手機 HTTP server 自行解析請求，限制 body 為 64 KiB、單行為 8 KiB，socket 讀取等待為 10 秒，每次回應使用 `Connection: close`。64 KiB 限制的是請求，不是圖片回應大小；能力 schema 允許的字串長度也不代表傳輸層能接收同等大的封包。

Relay 程式目前沒有自行實作每位呼叫者的速率限制、請求 body 上限或等待佇列總數上限。Cloudflare 本身的限制與配額仍適用，但不能當成此專案已有完整的流量與濫用防護。

## 手機端仍會檢查什麼

請求到達手機後，仍受 Hyper Mode、能力設定、Android 權限與 PickPico 核准模式控制。

核准模式依指令登記的 `sideEffect` 與 `risk` 分類判斷，不會理解每個 App 畫面中的交易金額或業務意義。`YOLO` 也不會提供 Root、ADB 或繞過 Android 安全驗證的能力。

精確的模式行為與各能力限制見 [產品與能力說明](product-spec-v0.1.md)。

## Relay 維護與部署

目前配置位於 `relay/wrangler.jsonc`：

- Worker 入口：`relay/src/index.js`。
- Durable Object 綁定：`NODE_RELAY`，類別 `NodeRelay`。
- KV 綁定：`UPDATE_KV`，供更新資訊與 APK 分塊使用。
- Durable Object migration 已列在配置中。

現有 `account_id`、KV namespace ID 屬於專案配置。部署到另一個 Cloudflare 帳號前，必須改用該帳號的資源並保留相應綁定與 migration；只執行部署命令不會替你完成這些設定。

`relay/package.json` 提供 `dev`、`deploy`、`test` 指令。自架者還要設定 Android 的 Relay 基底網址、重新啟動服務，再從新環境測試完整的手機請求。

Relay 同時提供 `/v1/update/latest`、`/v1/update/files/...` 與 `/u.apk`。有 Relay 健康回應不代表 KV 已發布更新檔；自架未準備更新資料時，更新端點可能回 404。

## 核對原始碼

- [Relay 路由、驗證與等待](../relay/src/index.js)
- [Android RelayClient](../app/src/main/java/com/mcpocket/poc/RelayClient.java)
- [Local HTTP server](../app/src/main/java/com/mcpocket/poc/McpHttpServer.java)
- [MCP 協定處理](../app/src/main/java/com/mcpocket/poc/McpProtocol.java)
- [能力執行與核准判斷](../app/src/main/java/com/mcpocket/poc/CommandRuntime.java)

[回到 README](../README.md)
