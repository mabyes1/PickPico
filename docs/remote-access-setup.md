# PickPico Remote Access Setup Notes

這份文件用來說明 PickPico 的 Remote Access 到底需要什麼，以及未來應如何向使用者解釋。

它不是完成版 onboarding UI spec，也不是要求目前版本立刻實作 Remote setup wizard。目的是先把產品與架構邊界講清楚，讓後續的人類或 AI 能在不猜測的情況下繼續設計。

## 1. 預設產品狀態

PickPico 安裝後，手機本身即可提供 Local MCP：

```text
Local Agent / Desktop Agent
        |
        | HTTP + Bearer
        v
Android phone :8765/mcp
```

因此不需要任何 Relay，PickPico 就可以先處於：

```text
READY · LOCAL

Remote access
Not configured
```

`Remote access: Not configured` 不是錯誤。它只是表示目前只有區域網路內的 Agent 能抵達這支手機。

## 2. 為什麼 Remote 需要額外基礎設施

ChatGPT Web、Claude.ai 等雲端 Agent 無法直接連進使用者家中的 LAN，也不能直接存取 `192.168.x.x:8765`。

PickPico 的 Remote Transport 因此採用反向連線：

```text
Cloud Agent
    |
    | HTTPS
    v
Compatible PickPico Relay
    ^
    | WSS initiated by phone
    |
PickPico Android
    |
    | HTTP loopback
    v
127.0.0.1:8765/mcp
```

手機主動向 Relay 建立 WSS，因此不需要 public IP、port forwarding 或額外 VPN。

## 3. 什麼叫「Compatible PickPico Relay」

Remote Access 並不是要求使用者一定要使用 Cloudflare。

真正的產品需求是：

> Relay backend 必須實作 PickPico Remote Transport protocol，能接受遠端 MCP request，找到對應的手機連線，並透過手機主動建立的連線把 request 轉交給 Android 上的 MCP runtime。

目前 repo 內的 `relay/` 是官方 reference implementation，技術上使用：

```text
Cloudflare Worker + Durable Object
```

目前專案自己操作的 demo relay 是：

```text
https://relay.pickpico.workers.dev
```

這個 relay 適合 hackathon、內測與專案 demo，但不應被視為所有開源使用者永久免費共用的公共基礎設施。

## 4. 一般使用者要怎麼取得 Remote Access

未來至少應支援一條清楚的自架路徑。

目前 reference implementation 的概念流程是：

```text
1. 取得 PickPico repo
2. 進入 relay/
3. 在自己的 Cloudflare account 部署 Worker / Durable Object
4. 取得自己的 relay base URL
5. 將 relay URL 設定進 PickPico
6. PickPico 產生或使用自己的 Node ID / relay secret
7. 手機連上 relay
8. 產生可供外部 Agent 使用的 Remote MCP URL
```

目前 repo 的基本部署方式：

```bash
cd relay
npm install
npx wrangler login
npx wrangler deploy
```

實際畫面、QR code、copy/paste 流程、是否能一鍵部署等，都留給後續 UI / onboarding 設計決定。

## 5. 未來說明頁必須讓使用者理解的事

Remote setup 頁面不需要把使用者訓練成網路工程師，但至少要清楚說明：

- `Local` 不需要任何雲端服務，裝好即可使用。
- `Remote` 需要一個相容的 Relay backend。
- PickPico 提供 reference relay implementation，但預設不保證提供永久公共 hosted relay。
- Remote Relay 可以自架，不必依賴 PickPico 專案作者的 Cloudflare 帳號。
- Cloudflare 只是目前的 reference implementation，不是 protocol 本身。
- Remote URL 應視同 credential，不應公開張貼。
- Local bearer token、Relay secret、Remote capability URL 是不同用途的 credential，不應混為同一個東西。

## 6. UI 層級建議，目前只做記錄、不要求立即實作

首頁只需要讓使用者知道：

```text
READY · LOCAL

Remote access
Not configured
```

之後若要做 Remote setup，可以由 `Remote access` 區塊進入一個獨立說明 / 設定頁。

該頁的第一層應先解釋產品概念，再讓進階使用者選擇或輸入 Relay。不要一打開就直接丟出 Worker、Durable Object、WSS、Node ID 等底層術語。

完整 transport 細節仍應放在：

```text
Settings → Developer / Diagnostics
```

## 7. 目前尚未決定的項目

以下都還沒有定案，不應由後續實作者自行假設：

- 是否提供官方 hosted relay 方案。
- 是否提供一鍵 Cloudflare deploy。
- 是否支援其他 relay provider / self-host template。
- Remote setup 是否採 wizard、QR code 或 deep link。
- 如何替非技術使用者解釋 self-host。
- 是否自動偵測 Local / Relay 並替 Agent 選擇 transport。
- Remote setup 完成後首頁的最終視覺樣式。

這些等後續 UI / product 討論再逐項決定。
