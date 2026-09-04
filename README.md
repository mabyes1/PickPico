# PickPico

> **Give your agent a phone.**

Turn any Android phone into an AI Agent's **eyes, hands, runtime, and human connection**.

PickPico 把 Android 手機變成可被 AI Agent 使用的 **Mobile Agent Node**。Agent 不只會呼叫雲端 API，也能透過手機取得相機、麥克風、位置、通知與目前畫面，操作 App、執行程式，甚至在真的需要人類時正式發出 **HUMAN HELP**，取得文字、選項或照片後再繼續原任務。

它不是另一套手機遙控器。PickPico 想解的是更直接的問題：

> **AI Agent 已經活在雲端裡了，怎麼讓它跨進真實世界？**

FUTUREMODE × SITCON Hackathon 2026 · AI Agents & Automation

## 30 秒看懂

AI Agent 通常已經有瀏覽器、程式碼、Cloud API 和大量工具，但它對身邊的世界幾乎是瞎的。

Android 手機剛好是一台已經大量存在的 edge computer：

- 有相機、麥克風、GPS、螢幕、喇叭、震動與通知。
- 有 LINE、Maps、瀏覽器、相簿、行事曆等真實世界 App。
- 有電池、Wi-Fi、行動網路，原本就適合長時間在線。
- 還有一個最重要的周邊：**手機旁的人類。**

PickPico 透過 MCP 把這些能力整理成 Agent 可以動態探索、呼叫、組合的能力面。

## 為什麼是 PickPico

多數 Agent 工具是在替 AI 增加「可以呼叫什麼」：更多 API、更多 function、更多 automation。

PickPico 想做的不是再加一包 Android tools，而是把一支真實世界裡的 Android 手機變成 **Agent hardware platform**。相機、麥克風、GPS、螢幕、喇叭、震動、行動網路、Apps、運算環境，甚至手機旁的人，都可以成為同一個 Agent workflow 的一部分。

> **Most Agent tools extend what an AI can call. PickPico extends where an AI can exist.**

所以 Agent 得到的不只是「遠端控制手機」，而是一個能感知、能行動、能執行程式，也能在必要時把任務交給真人處理的 **Mobile Agent Node**。

```text
AI Agent
   │
   │ MCP
   ▼
PickPico
   ├─ Sense   → camera / mic / location / screen / notifications
   ├─ Act     → apps / UI / notification / speech / share sheet
   ├─ Run     → workspace / shell / background process / Node.js
   └─ Ask     → HUMAN HELP → nearby human
```

## PickPico 能做什麼

| 類別 | 已實作能力 |
| --- | --- |
| **Sense** | 相機拍照、麥克風錄音、GPS、螢幕擷取、通知讀取、Accessibility UI tree |
| **Act** | App 啟動、網址 / deep link、點擊、輸入、捲動、剪貼簿、通知、TTS、響鈴、喚醒 |
| **Personal data** | 聯絡人查詢、行事曆讀寫、通知 action / reply |
| **Files & media** | 系統 File Picker、Photo Picker、PickPico 私有 workspace、Android Share Sheet |
| **Run** | App sandbox 內 shell、背景程序、內嵌 Node.js runtime |
| **Human** | HUMAN HELP：文字、選項、相簿選圖、現場拍照、可續時等待 |
| **Long tasks** | 建立、更新與追蹤跨多個 command / human handoff 的 Agent task |
| **Remote** | 手機主動連線 Cloudflare Relay，可跨 Wi-Fi / 行動網路使用 |
| **Update** | 遠端檢查新版、下載 APK、驗證後交給 Android installer |

### Core Mode / Hyper Mode

PickPico 把高權限能力另外放進 **Hyper Mode**。這些能力不是偷偷取得，而是必須由手機持有者在 Android 上明確開啟對應設定。

目前 Hyper 能力包含：

- Accessibility UI inspect / click / type / scroll
- active notifications read / action / reply
- MediaProjection screen capture
- Device Admin phone lock
- urgent full-screen Agent handoff
- 在 Hyper Mode 開啟時請 Android 嘗試 dismiss keyguard

最後一項**不會繞過 PIN、圖形、指紋或其他安全驗證**；是否能 dismiss lock screen 仍由 Android Keyguard 決定。

## 核心 Demo

### 1. Agent 卡住時，不要死轉圈，直接叫人

PickPico 的 **HUMAN HELP** 是 Agent 面對真實世界阻礙時的標準出口。

例如 Agent 遠端維護設備時需要知道「面板綠燈到底有沒有亮」，與其反覆猜測，它可以直接把任務交給手機旁的人：

```json
{
  "commandId": "human.help",
  "arguments": {
    "title": "請協助確認設備狀態",
    "instruction": "請看面板上的綠燈是否亮起；如果不確定，可以直接拍照。",
    "actions": ["綠燈有亮", "沒有亮", "無法確認"],
    "allowTextReply": true,
    "allowImages": true,
    "maxImages": 3,
    "idleTimeoutSeconds": 180
  }
}
```

使用者開啟任務、輸入文字、選照片或拍照時都會更新 idle timer，避免人類正在幫忙時 request 卻先死掉。

### 2. Pocket Worker：讓閒置手機本身成為 Agent 的運算節點

Agent 可以在 PickPico 私有 workspace 寫檔、執行 shell，或啟動一個長駐 Node.js entry point。

這代表一些小型 automation / bot / utility 不一定要靠機房，也不一定要讓家裡桌機 24 小時不關機；一支插著電、連著網路的 Android 手機就能成為實際執行節點。

```text
Agent → workspace.write → node.start → Android phone keeps the workload alive
```

### 3. 沙發 Vibe Coding

PickPico 也有 BLE button bridge，可搭配 Pico 手機架 / 實體按鈕，把手機變成 Agent 的隨身終端：按住說話、放開確認，再把語音需求送進 Agent。

你不一定需要坐在電腦前才能叫 Agent 寫程式。手機本身就是入口。 📱🛋️

### 4. 手機端小模型 + PickPico

PickPico 的 MCP capability layer 與上層模型解耦。架構上可以讓 4B 級左右的手機端模型使用同一組 capabilities，變成真正能操作這支手機的 local assistant。

> 這是目前的延伸方向，不代表 PickPico APK 已內建特定本地模型。

## 系統架構

```mermaid
flowchart LR
    AGENT[AI Agent / MCP Client]
    RELAY[Cloudflare Relay]
    PHONE[Android PickPico]
    GATEWAY[Thin MCP Gateway]
    RUNTIME[Capability Runtime]
    DEVICE[Android Device + Apps]
    NODE[Workspace + Node.js]
    HUMAN[HUMAN HELP]

    AGENT -->|HTTPS| RELAY
    PHONE -->|Outbound WSS| RELAY
    RELAY --> GATEWAY
    GATEWAY --> RUNTIME
    RUNTIME --> DEVICE
    RUNTIME --> NODE
    RUNTIME --> HUMAN
    HUMAN -->|Reply / Image| AGENT
```

手機主動建立 outbound connection，所以不需要 public IP、port forwarding 或 VPN。

```text
Remote: Agent ─HTTPS→ Cloudflare Relay ←WSS─ PickPico
Local:  Agent ─HTTP + Bearer→ Android :8765/mcp
```

目前 reference Relay：

```text
https://relay.pickpico.workers.dev
```

## 為什麼 MCP 工具只有少數幾個

PickPico 不會把每一項手機能力都做成頂層 MCP tool。

公開的 `/v3` thin profile 只保留 10 個穩定入口：

```text
server_info
capability_search
capability_status
policy_status
command_run
command_status
task_runtime_info
task_create
task_update
task_status
```

Agent 需要能力時才搜尋：

```text
capability_search("看看現在手機畫面")
  → screen.capture / ui.inspect

command_run("screen.capture")
  → native MCP image
```

這樣新增手機能力時不必一直膨脹 tool list，也降低模型在一大排相似工具裡選錯的機率。

舊版 `/v1`、`/v2` 仍保留較完整的 tool surface 以維持相容性。

## 安全邊界

PickPico 的原則不是「Agent 想做什麼都可以」，而是把能力放在 Android 原生權限與明確 owner policy 之內。

| 能力 | 邊界 |
| --- | --- |
| 相機、麥克風、位置、聯絡人、行事曆 | 依 Android runtime permission 管理 |
| 通知存取 | 必須由使用者在 Android 設定中開啟 Notification Listener |
| Hyper UI | 必須由使用者親自在系統設定中啟用 Accessibility Service |
| Screen capture | 必須取得 Android MediaProjection 授權 |
| Phone lock | 必須先啟用 Device Admin |
| Hyper unlock | 只要求 Android dismiss keyguard，不繞過 secure authentication |
| Shell / Node.js | 僅在 PickPico App sandbox 內執行，沒有 root 或 ADB 權限 |
| HUMAN HELP | 回覆與圖片保存在 App 私有儲存空間 |
| Local MCP | 使用 Bearer token |
| Remote MCP | 高熵 capability URL、Relay secret 與 local Bearer 分開管理 |
| Self update | 驗證 SHA-256、package name、簽章與版本後才交給 Android installer |

**遠端 capability URL 本身等同憑證，不應公開分享。**

## 連線方式

### Local

1. 手機與 MCP Client 位於相同的可信任網路。
2. 開啟 PickPico，啟動 Node。
3. 複製 App 顯示的 MCP Connection JSON 到 Client。

### Remote Relay

1. Relay URL 使用 `https://relay.pickpico.workers.dev`。
2. 啟動 Node，等待 Relay 顯示 connected。
3. 複製 Remote MCP Connection JSON 到 Client。
4. 手機之後可在 Wi-Fi / 行動網路間切換並自動重連。

更完整的傳輸、安全設計與自架說明：[`docs/remote-transport.md`](docs/remote-transport.md)

## Build

目前 Android build：**0.16.27** (`versionCode 64`)

需求：

- Java 17
- Gradle 8.7
- Android SDK 35
- Android NDK `27.3.13750724`
- CMake `3.22.1`
- Android 8.0+ / `arm64-v8a`

第一次 build 會下載 `nodejs-mobile 18.20.4`，並在解壓前驗證固定 SHA-256。

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug
```

APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Hackathon readiness

```powershell
# Build + unit tests + APK + Relay health
pwsh scripts/hackathon-readiness.ps1

# 加上真實 Android MCP Node
pwsh scripts/hackathon-readiness.ps1 -Device

# 再加 HUMAN HELP 完整 round trip
pwsh scripts/hackathon-readiness.ps1 -Device -HumanHelp
```

完整展示流程：[`docs/demo-runbook.md`](docs/demo-runbook.md)

## 專案結構

```text
MCPocket/
├─ app/                         Android PickPico
├─ relay/                       Cloudflare Worker + Durable Object
├─ firmware/
│  └─ pickpico-button-pad/      BLE physical button pad
├─ docs/
│  ├─ product-spec-v0.1.md      Product / capability spec
│  ├─ remote-transport.md       Remote transport & security
│  ├─ remote-access-setup.md    Remote setup
│  └─ demo-runbook.md           Hackathon demo flow
└─ scripts/                     Build / validation / publishing
```

## Tech

- Model Context Protocol
- Android SDK / AndroidX
- OkHttp
- nodejs-mobile
- Cloudflare Workers / Durable Objects / Workers KV
- BLE
- JUnit

本專案使用 AI coding agents 協助開發與測試；產品設計、整合與驗證由團隊負責。第三方元件各自適用其原始授權條款。

## License

PickPico 原始碼採用 [MIT License](LICENSE)。
