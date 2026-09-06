<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/icon1.png">
    <source media="(prefers-color-scheme: light)" srcset="app/src/main/res/icon2.png">
    <img src="app/src/main/res/icon2.png" alt="PickPico" width="320">
  </picture>
</p>

# PickPico

> **Give your agent a phone.**

**讓 AI 看見現場、操作手機、執行程式，遇到難題直接找你。**

FUTUREMODE × SITCON Hackathon 2026 · **AI Agents & Automation**

## 問題與目標

今天的 AI Agent 已經可以寫程式、查資料、操作雲端服務，卻常在碰到真實世界的最後一公尺時停下來。

它可能需要看一眼設備面板、拍一張照片、讀取手機通知、打開 App 完成幾個操作，甚至只需要請旁邊的人按一下按鈕。這些事情對人類來說很簡單，對只活在瀏覽器或伺服器裡的 Agent 卻可能變成任務中斷點。

**PickPico 把 Android 手機變成 AI Agent 可以直接使用的行動節點。**

手機本來就有相機、麥克風、定位、螢幕、網路、電池與運算能力，而且通常就在使用者身邊。PickPico 透過 MCP 把這些能力接進 Agent 的工作流程，讓 Agent 能自行探索手機目前可用的能力、執行操作，必要時再把最適合由人完成的那一步交給人類。

```text
AI Agent
   │
   ├─ 看見現場
   ├─ 操作手機
   ├─ 在手機上執行程式
   └─ 需要時向人類求助
            │
            └─ 回覆後繼續原本任務
```

PickPico 的目標不是做另一個「遠端遙控手機」工具，而是讓手機成為 **Agent 走進真實世界的入口**。

## 核心功能

| 能力 | 能做什麼 | 使用情境 |
| --- | --- | --- |
| 👀 **看現場** | 相機、錄音、位置、螢幕擷取 | 「1 分鐘後拍照，人走到定位後，發出語音倒數 3、2、1 再拍照。」 |
| 👆 **動手機** | 開啟 App、點擊、輸入、捲動、語音播報 | 「打開 Uber，填好上下車地點，停在送出前讓我確認。」 |
| ⚙️ **跑程式** | 讀寫檔案、執行指令、啟動 Node.js 程式 | 「在手機上寫一個小工具，執行後把結果告訴我。」 |
| 🙋 **HUMAN HELP** | 發出求助、接收文字／選項／照片／相機回覆 | Agent 判斷某一步讓旁邊的人處理更快時，把完整操作需求交給人類，再接續任務。 |
| 🧭 **動態能力探索** | Agent 先搜尋能力、確認可用狀態與輸入格式，再執行 | 不需要把數十個手機工具一次塞進模型 context。 |
| 🛡️ **Core / Hyper Mode** | 將一般手機能力與高權限畫面操作分層 | 只有真的需要 UI 操作、通知存取或螢幕擷取時才開啟進階權限。 |

### HUMAN HELP：讓人類成為工作流程的一部分

案例：請 Agent 協助檢查設備

1. Agent 透過手機拍攝設備面板。
2. 畫面看不清楚，Agent 判斷讓附近的人補拍最有效率。
3. PickPico 發出求助：「請靠近面板拍一張照片。」
4. 手機旁的人直接拍照並回覆。
5. Agent 收到照片，繼續判讀與後續工作。   

人類可以回覆文字、點選選項、選擇照片，或直接拍攝現場；操作期間會自動延長等待時間。


**HUMAN HELP 不是任務的終點，而是 Agent 可以主動使用的一個能力。**

### 手機也能成為 Agent 的執行環境

PickPico 內建 Node.js Mobile runtime。Agent 可以在 App 私有工作空間寫入檔案、執行指令、啟動 Node.js 小工具，也支援背景程序與檔案管理。

```text
交代需求 → Agent 寫入程式 → 手機執行 → 取得結果
```

案例：臨時網站架設

人在沒有電腦的環境下，臨時需要一個可公開瀏覽的產品介紹頁。
1. Agent 直接在手機沙盒建立頁面，遇到需要設計或人類判斷的內容時發出求助。
2. 在手機啟動 localhost server。
3. 將服務穿透成 public URL。
4. 交付臨時網站。


### 手機開發自迭代：讓手機成為開發節點

PickPico 也可以把手機接回 AI coding agent 的開發迴圈。當 Agent 修改手機 App、行動版網頁或其他需要真機驗證的功能後，不必只停在模擬器、console log，或等待人類逐步描述畫面；它可以透過 PickPico 直接觀察與操作真實手機，再根據結果繼續修正。

```text
Agent 修改程式
      ↓
部署 / 更新到手機
      ↓
PickPico 在真機上觀察、點擊、滑動、驗證
      ↓
Agent 取得結果並繼續修正
      ↺
```

這讓「寫程式 → 真機測試 → 發現問題 → 再修改」形成更完整的 Agent 自迭代迴圈，尤其適合 UI、通知、鎖定畫面、相機、麥克風與其他只有真實手機環境才能完整驗證的行為。

PickPico 並不是要宣稱手機開發從此不需要電腦，而是嘗試把原本高度 **PC-centric** 的開發流程往 **PC-optional** 推進：讓手機不再只是「被開發、被測試的裝置」，而是能直接參與 Agent 工作流程的開發節點。

> **Give your agent a phone, so it can build for phones.**

### 不需要 Root，也不用開 ADB

PickPico 使用 Android 正式提供的權限與服務，不要求 Root，也不需要開啟 ADB／USB 偵錯。

- 相機、麥克風、位置等能力由 Android 權限管理。
- 畫面操作、通知存取與螢幕擷取需另外授權。
- PIN、圖形、指紋等裝置解鎖驗證仍由 Android 處理。
- 檔案與程式在 PickPico 的 App 私有空間內運作。
- 高權限操作集中在 **Hyper Mode**，由使用者明確啟用。

### 選配：Pico 手機架與 BLE 實體按鈕

PickPico 也能搭配手機架與 BLE 實體按鈕，讓手機成為隨手可用的 Agent 終端。

四顆按鈕為：**允許／拒絕／細節／語音輸入**。

有等待中的 HUMAN HELP 任務時，**按住語音鍵錄音、放開送出語音回覆**。

手機架與實體按鈕為選配，不影響 PickPico 的 MCP 核心功能。

## 系統架構

```mermaid
flowchart LR
    A[AI Agent / MCP Client]
    C{連線方式}
    R[Self-hosted Cloudflare Relay<br/>Worker + Durable Object]
    P[PickPico Android Node]
    D[Capability Discovery<br/>& Approval Policy]
    CORE[Core Capabilities<br/>Camera · Location · Files<br/>Node.js · Share · Calendar]
    HYPER[Hyper Mode<br/>UI · Screen Capture<br/>Notifications · Home]
    HUMAN[HUMAN HELP<br/>Text · Choice · Photo · Camera]
    BLE[Optional BLE Button Pad]
    U[Human]

    A -->|MCP| C
    C -->|LAN HTTP + Bearer token| P
    C -->|Remote MCP URL| R
    R <-->|WebSocket / request lifecycle| P
    P --> D
    D --> CORE
    D --> HYPER
    D --> HUMAN
    BLE --> P
    HUMAN <--> U
    P -->|Result / media / task state| A
```

### 動態能力探索

PickPico 不把所有手機工具一次暴露給 Agent，而是提供穩定的能力探索與執行入口。Agent 先搜尋任務需要的能力，再確認狀態、權限與輸入格式後呼叫。

```text
「看看手機畫面」
       ↓
搜尋螢幕相關能力
       ↓
確認目前是否已授權
       ↓
擷取畫面或讀取介面元素
       ↓
依結果繼續工作
```

目前能力包含相機、麥克風、位置、UI 操作、螢幕擷取、通知、聯絡人、行事曆、分享面板、檔案／照片選取、語音播報、音量、手機喚醒、Node.js 程式執行，以及跨步驟 Agent task 狀態管理等。

### Local 與 Remote

```text
區域網路：Agent ─────────────→ PickPico

遠端連線：Agent → Cloudflare Relay ← PickPico
```

手機會主動連上 Relay，因此不需要公開 IP、路由器 port forwarding 或 VPN。

**本專案不提供公共 Relay。** 遠端使用需自行部署，完整 MCP URL 本身具有存取能力，請勿公開分享。

更多設計細節：

新增手機能力不需要持續擴張頂層工具數量，也能避免 Agent 因工具清單過長而選錯工具。舊版 `/v1`、`/v2` 端點保留完整工具介面以維持相容性。

## 已實作能力

| 類別 | 能力 |
| --- | --- |
| 裝置感知 | 相機拍照、麥克風錄音、位置、螢幕擷取、通知讀取 |
| 手機互動 | 通知、語音、響鈴、喚醒、App 啟動、網址、剪貼簿 |
| Hyper UI | 介面結構讀取、點擊、輸入、捲動、通知動作與回覆 |
| HUMAN HELP | 文字、選項、相簿選圖、現場拍照、等待期間自動續時 |
| 執行環境 | 私有工作區、Shell、背景程序、內嵌 Node.js |
| Agent 任務 | 建立、更新與追蹤長時間任務狀態 |
| 遠端連線 | 手機主動連線、固定能力網址、Wi-Fi／行動網路切換重連 |
| 更新 | APK 下載、雜湊與簽章驗證、Android 安裝確認 |

目前 Android source 版本：**0.16.47**（`versionCode 84`），支援 Android 8.0 以上的 `arm64-v8a` 裝置。

## HUMAN HELP

HUMAN HELP 是 Agent 面對真實世界阻礙時的標準出口：

```json
{
  "commandId": "human.help",
  "arguments": {
    "title": "請協助確認設備狀態",
    "instruction": "請查看面板上的綠燈是否亮起；若不確定，可以直接拍照。",
    "actions": ["綠燈有亮", "沒有亮"],
    "allowTextReply": true,
    "allowImages": true,
    "maxImages": 3,
    "idleTimeoutSeconds": 180
  }
}
```

等待時間以使用者最後一次操作計算。開啟任務、輸入文字、選圖或拍照都會延長等待時間，避免在人類正在處理時過早結束。

## 安全邊界

PickPico 遵守 Android 原生權限與安全機制：

| 能力 | 邊界 |
| --- | --- |
| 相機、麥克風、位置 | 由使用者授權；相機與麥克風另檢查當下媒體服務，未就緒回報 `setup_required`，請使用者開啟 App 完成授權或刷新 |
| 通知存取 | 必須由使用者在 Android 設定中開啟 |
| Hyper UI | 必須由使用者親自在系統設定中啟用 Accessibility Service |
| 鎖定／回桌面 | 鎖定需先啟用 Device Admin；`phone.home` 不能繞過 Android PIN、圖形、指紋或其他 secure keyguard 驗證 |
| Shell | 僅在 PickPico App sandbox 內執行，不具備 root 或 ADB 權限 |
| HUMAN HELP | 回覆與圖片保存在 App 私有儲存空間 |
| 本地連線 | 使用 App 顯示的 Bearer token 驗證 |
| 遠端連線 | 高熵能力網址、Relay secret 與本地 Bearer 分別管理 |
| App 更新 | 驗證 SHA-256、套件名稱、簽章與版本後交給 Android 安裝 |

遠端能力網址等同憑證，不應公開分享。

`phone.wake` 只負責喚醒顯示，不解鎖手機，也不代表背景保活或永久常亮；`phone.home` 會嘗試把裝置帶到可操作的 Home 狀態，若 Android 要求安全驗證則回報需要使用者操作。

## 建置

需要 Java 17、Gradle 8.7、Android SDK 35、Android NDK `27.3.13750724` 與 CMake `3.22.1`。第一次建置會下載 nodejs-mobile 18.20.4，並在解壓前驗證固定 SHA-256。

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug
```

產出的 APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 連線方式

### 區域網路

1. 讓手機與 MCP Client 位於相同的可信任網路。
2. 開啟 PickPico 並按下 **Start node**。
3. 到 **SETTINGS → Developer / Diagnostics** 取得 Local MCP 網址與 Local bearer，將網址及 `Authorization: Bearer <token>` 加入 MCP Client。

### 遠端 Relay

1. 先部署自己的 Relay，再在 PickPico 輸入其 HTTPS 基底網址。
2. 啟動 Node，等待 `RELAY STATUS = CONNECTED`。
3. 複製 Connection JSON；手機之後可在 Wi-Fi 與行動網路間切換。

本次 Hackathon 展示使用的 Relay（僅供專案展示，不代表公共服務）：

```text
https://relay.pickpico.workers.dev
```

通訊、安全設計與自架說明位於 [`docs/remote-transport.md`](docs/remote-transport.md)。

## 驗證

請將範例網址換成自己的 Relay；Windows 腳本內的 SDK／JDK 路徑須符合本機環境。`-Device` 是開發驗證選項，一般 MCP 操作不需要 ADB。

```powershell
# 建置、單元測試、APK 與 Relay 健康檢查
pwsh scripts/hackathon-readiness.ps1 -RelayBaseUrl 'https://your-relay.example.com'

# 連接 Android debug 裝置後驗證真實 MCP Node
pwsh scripts/hackathon-readiness.ps1 -RelayBaseUrl 'https://your-relay.example.com' -Device

# 加上 HUMAN HELP 完整往返
pwsh scripts/hackathon-readiness.ps1 -RelayBaseUrl 'https://your-relay.example.com' -Device -HumanHelp
```


## 專案結構

```text
PickPico/
├─ app/                         Android PickPico
├─ relay/                       Cloudflare Worker + Durable Object
├─ docs/
│  ├─ product-spec-v0.1.md      產品規格
│  ├─ remote-transport.md       遠端傳輸與安全設計
│  └─ demo-runbook.md           展示流程
└─ scripts/                     建置、驗證與發布工具
```
更多文件：

- [遠端連線指南](docs/remote-access-setup.md)
- [遠端傳輸與安全設計](docs/remote-transport.md)
- [產品與能力規格](docs/product-spec-v0.1.md)

## 使用技術

| 類型 | 技術／服務 | 用途 |
| --- | --- | --- |
| AI / Agent | MCP-compatible Agent；本次開發與展示使用 OpenAI ChatGPT / Codex | 規劃任務、探索能力與呼叫手機工具 |
| Protocol | Model Context Protocol (MCP) | Agent 與 PickPico 的標準工具介面 |
| Android | Java、Android SDK 35、Accessibility、MediaProjection、Foreground Service 等 | 手機能力、權限與 UI 操作 |
| On-device runtime | Node.js Mobile 18.20.4 | 在 App 私有空間執行 Node.js 程式 |
| Networking | OkHttp、HTTP、WebSocket | Local MCP 與 Relay 長連線 |
| Backend | Cloudflare Workers、Durable Objects、KV | 遠端 Relay、Node lifecycle 與更新資料 |
| Hardware | BLE | 選配 Pico 實體按鈕 |
| Sponsor 技術 | OpenAI ChatGPT / Codex | Agent client、開發與實機測試；PickPico runtime 本身不綁定單一模型供應商 |

## 安裝與執行

### 方式一：直接安裝 APK

準備：

- Android 8.0（API 26）以上
- ARM64 (`arm64-v8a`) 裝置
- 支援 MCP 連線的 Agent / Client

步驟：

1. 安裝 PickPico APK 並開啟 App。
2. 在首頁啟動 Node service。
3. 依任務需求開放相機、位置等 Android 權限，完成後回到 App 刷新媒體服務。
4. 需要操作手機 UI、讀取通知或擷取螢幕時，再啟用 **Hyper Mode** 並依畫面完成系統授權。
5. 選擇 Local 或 Remote 連線方式。
6. 將 App 顯示的 MCP 連線資訊加入 Agent。
7. 先呼叫 `server_info`，再請 Agent 搜尋手機能力並執行第一個任務。

建議第一個測試：

> 「看看這支手機現在有哪些能力，再幫我用前鏡頭拍一張照片。」

完整設定流程見 [PickPico 連線指南](docs/remote-access-setup.md)。

### 方式二：從原始碼建置 Android App

建置需求：

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.5.2
- Android SDK 35
- Android NDK `27.3.13750724`
- CMake 3.22.1
- 可連線下載 Node.js Mobile runtime

目前 repository 未內含 Gradle Wrapper；請使用本機 Gradle 8.7 或 Android Studio 對應環境。

以下指令需已有 Android debug keystore；首次建置可省略 `-PpickpicoDebugKeystore`，使用自動建立的預設除錯金鑰。更新既有安裝時，必須沿用相同簽章：

```powershell
gradle "-PpickpicoDebugKeystore=$env:USERPROFILE\.android\debug.keystore" `
  :app:testDebugUnitTest :app:assembleDebug
```

輸出 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

建置期間會下載官方 Node.js Mobile 18.20.4 Android runtime，並驗證 SHA-256 後才解壓使用。

### 部署自己的 Remote Relay

遠端使用需要自己的 Cloudflare Workers 環境與 KV namespace：

```bash
cd relay
npm ci
npx wrangler login
```

接著：

1. 複製 `relay/wrangler.jsonc` 為 `relay/wrangler.local.jsonc`。
2. 建立自己的 KV namespace，填入對應 ID。
3. 保留 Durable Object `NODE_RELAY` 綁定與 migration。
4. 部署：

```bash
npx wrangler deploy --config wrangler.local.jsonc
```

5. 將部署後的 HTTPS 基底網址填入 PickPico 的 **Remote Access**。
6. 等待 App 顯示 `CONNECTED`，再從 Agent 呼叫 `phone.status` / capability tools 驗證完整鏈路。

詳細說明見 [Relay 自行部署文件](relay/README.md)。

## 作品展示

- Repository：<https://github.com/mabyes1/PickPico>
- 評選影片：https://youtu.be/Y9fLNbH9LDE

## 限制與未來工作

### 目前限制

- 目前 Android App 僅支援 Android 8.0+、ARM64；尚未支援 iOS。
- UI 操作、通知存取與螢幕擷取受 Android 權限與前景執行限制，部分操作仍需要使用者先完成系統授權。
- PickPico 不繞過 Android PIN、圖形、指紋或其他鎖屏安全機制。
- Remote Relay 目前提供 Cloudflare Worker + Durable Object 實作，尚未提供可直接部署的 Docker / Node.js server 版本。
- 不同 MCP Client 對圖片、音訊與互動內容的呈現能力不同。
- 本專案不提供公共 Relay，遠端使用者必須自行部署並管理存取網址。

### 未來工作

- 提供更通用的 self-hosted Relay / Docker 部署方式。
- 建立 capability extension SDK，讓 App 或硬體能力可以被第三方擴充。
- 強化離線與 local LLM 情境，讓手機本身可以同時成為 Agent runtime 與 physical interface。
- 擴充 BLE / 感測器／硬體節點，讓 Agent 不只「有一支手機」，也能逐步取得更多實體世界介面。
- 改善跨裝置 task persistence、斷線恢復與長時間 Agent 工作流程。

#### Android 原生 OpenAI Tunnel

PickPico 現在使用自行部署的 Relay，讓手機可以獨立連上網路並被遠端 Agent 使用，同時保持對不同 MCP Client 與模型供應商的相容性。

OpenAI Secure MCP Tunnel 提供另一條路徑：對 ChatGPT 使用者而言，可以省去自行部署公開 Relay 的需求。不過目前官方 Tunnel Client 主要運行在電腦或伺服器環境；若透過電腦中轉，會重新引入 PickPico 想移除的常駐電腦依賴。

因此未來希望能在 **Android 端直接實作 OpenAI Tunnel 相容的 transport**：

```text
ChatGPT → OpenAI Tunnel → PickPico Android
```

這樣 ChatGPT 可以在 **不需要自架 Cloudflare Relay，也不需要另一台常駐電腦** 的情況下直接使用手機；現有 PickPico Relay 則繼續保留，提供其他 MCP Client 與本地模型使用。

目標不是把 PickPico 綁定單一平台，而是讓 Agent 可以依環境選擇最適合的 transport。

## 第三方服務、資料與素材

本專案不應提交 API key、Token、個人 Relay URL 或其他私人憑證。

| 項目 | 來源 | 用途／授權說明 |
| --- | --- | --- |
| Model Context Protocol | <https://modelcontextprotocol.io/> | Agent 工具協定；依官方規格與授權使用 |
| Android / AndroidX | <https://developer.android.com/> | Android App 與 Jetpack 元件；依 Android / AndroidX 個別授權 |
| Node.js Mobile | <https://github.com/nodejs-mobile/nodejs-mobile> | Android 內嵌 Node.js runtime；依上游專案授權使用，建置時驗證固定版本 SHA-256 |
| OkHttp | <https://square.github.io/okhttp/> | HTTP / WebSocket client；Apache License 2.0 |
| Cloudflare Workers / Durable Objects / KV | <https://developers.cloudflare.com/> | Self-hosted Remote Relay 與更新資料；依 Cloudflare 服務條款使用 |
| Wrangler | <https://developers.cloudflare.com/workers/wrangler/> | Relay 開發與部署工具；依上游專案授權使用 |
| JUnit 4 | <https://junit.org/junit4/> | Android JVM unit tests；依上游專案授權使用 |

PickPico Logo、產品文案與本專案自製介面素材由團隊製作。若使用第三方 App、服務或裝置進行 Demo，其商標與內容權利仍屬原權利人。

## 團隊成員

| Team member | Contribution |
| --- | --- |
| Ken Huang | Software product definition, system architecture, Android / MCP / Relay engineering, agent workflow, integration, testing, README & technical documentation |
| Kate Li | PickPico Dock product definition, industrial design, physical interaction design, Dock specification & design documentation, brand identity, UI/UX visual design, demo visual design & production |

本專案使用 OpenAI coding agents 協助開發、程式審閱與測試；產品方向、整合、實機驗證與最終決策由團隊負責。

## Repository 結構

| 目錄 | 內容 |
| --- | --- |
| `app/` | Android App 與手機 capabilities |
| `relay/` | Cloudflare Remote Relay |
| `firmware/` | BLE 實體按鈕韌體 |
| `docs/` | 產品、架構、設定與 Demo 文件 |
| `scripts/` | 建置、驗證與發布工具 |

## License

PickPico 採用 **AGPL-3.0 + Commercial Dual License**。

- 開源使用、修改與散布：依 **GNU Affero General Public License v3.0 (AGPL-3.0)**，完整條款見 [LICENSE](LICENSE)。
- 若要在不受 AGPL-3.0 copyleft 義務約束的專有／閉源產品或服務中使用、修改或整合 PickPico，請另行取得商業授權，詳見 [COMMERCIAL-LICENSING.md](COMMERCIAL-LICENSING.md)。

第三方元件仍依各自原始授權條款使用。
