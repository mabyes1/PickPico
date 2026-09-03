## 2026-09-03 — Theme editor uses a two-layer mental model and applies live

Appearance is presented as **Theme**, not `Glass Theme`.

The user-facing model has two layers:

```text
Theme
├── Background = the scene behind the UI
│   ├── Gradient
│   ├── Color A / Color B
│   └── Color strength
└── Glass = how cards reveal that scene
    ├── VibeDeck / Clear / Tunnel presets
    ├── Transparency
    └── Shine
```

- Presets are semantic shortcuts; the fine-tuning sliders remain available.
- Appearance changes apply live while the user edits them.
- Slider movement updates the visible UI immediately and persists when the drag finishes.
- Palette, preset, gradient, and color-picker choices persist automatically.
- There is no `APPLY APPEARANCE` button. Requiring a separate apply step is considered a UX bug for this editor.
- The editor should remain forgiving enough that casual experimentation tends to produce coherent results, but labels should still explain which visual layer each control affects.

# PickPico UI Decision Log

這份文件只記錄已經討論並形成共識的 UI / UX 取捨。

它不是完整 UI spec，也不是待辦清單。後續討論會逐步補充，不預先替尚未討論的畫面做決定。

## 2026-09-03 — Connection status / transport visibility

### 使用者真正需要知道的狀態

首頁應以「PickPico 是否已可供 Agent 使用」為主，不把 Local MCP、Remote Relay、WSS、Node ID、Bearer token 等底層 transport 細節直接暴露成主要操作項目。

主狀態採簡短形式：

```text
READY · LOCAL
```

或：

```text
READY · RELAY
```

若兩種 transport 同時可用，可顯示：

```text
READY · LOCAL + RELAY
```

### LOCAL / RELAY 的定位

- `READY` 是主要產品狀態。
- `LOCAL` / `RELAY` 只是輕量的 transport 狀態提示，不是模式切換器。
- 使用者不需要手動選擇 Local 或 Relay；PickPico 應自行維持可用的 transport。
- UI 不應讓人誤解成 `Local` 與 `Relay` 是兩個不同產品功能或兩種必須設定的工作模式。

### Remote 尚未設定時的首頁狀態

對一般開源安裝，不預設使用者一定擁有可用的 Remote Relay。若手機的 Local MCP 已可用、但 Remote access 尚未設定，首頁應清楚顯示：

```text
READY · LOCAL

Remote access
Not configured
```

這個狀態代表：

- PickPico 本體已可用。
- 同一 LAN 的本地 Agent / Desktop Agent 可連線。
- 雲端 Agent 尚不能從外部網路連進來。
- `Not configured` 是設定狀態，不是錯誤狀態，不應顯示成警告或故障。

若未來提供 Remote setup UI，入口可以從此區進入，但目前先不決定具體互動流程與按鈕樣式。

### 詳細連線資訊的位置

完整 transport / debugging 資訊移至：

```text
Settings → Developer / Diagnostics
```

此區才可顯示例如：

- Local endpoint
- Remote relay endpoint
- Remote MCP endpoint
- Relay connection state
- Node ID
- Local bearer token
- 其他連線診斷資訊

### 背後架構認知

PickPico 只有一套手機上的 MCP / Capability Runtime，Local 與 Relay 是抵達同一 Runtime 的不同 transport：

```text
Local Agent ─────────────→ Android MCP

Cloud Agent → Relay → WSS → Android MCP
```

因此 transport 是實作與診斷層資訊；正常使用時，UI 應優先呈現「Agent 現在能不能使用這支手機」。

## 2026-09-03 — Visual direction

PickPico 的視覺語言以現有 Tunnel-Coding 主控台為直接參考，目標是讓兩者看起來屬於同一個產品家族。

### 第一優先：玻璃材質

Tunnel-Coding 的核心參考不是單純「深色主題」，而是它的 **黑色半透明玻璃 / 霧面玻璃材質感**。這是 PickPico 視覺實作的第一優先。

- 面板要看起來是分層的半透明玻璃，不是實心深灰卡片。
- 玻璃感應來自適量透明度、背景模糊、很細的亮邊 / 內高光與層次，而不是大量漸層。
- 維持偏黑、偏技術工具的玻璃，不做亮白 iOS Liquid Glass，也不做紫藍 AI SaaS 霓虹風。
- Card、top bar、segmented control、dialog、status surface 應共享同一套玻璃材質語言。
- 綠 / 黃 / 紅等狀態色只是玻璃上的小面積訊號，不應蓋過玻璃本身。
- 如果「普通深色 UI」和「明確可見的玻璃深度」發生取捨，優先保留玻璃深度；只有黑底灰卡即使資訊架構正確，也視為視覺方向不正確。

簡化成一句：

```text
Tunnel-Coding 的黑玻璃材質
+ PickPico 的手機資訊架構
= 目標 UI
```

### 採用的風格特徵

- 深黑 / 近黑背景。
- 低對比灰色面板與細邊框，而不是高亮卡片堆疊。
- 白色主文字、灰色次要資訊。
- 綠色主要用於 ready / connected / enabled 等正常狀態。
- 紅色主要用於 stop / danger / destructive action。
- 小型 pill / badge 表示模式或狀態，例如 `YOLO MODE`、`LOCAL`、`RELAY`。
- 技術資訊可使用等寬字體，但一般產品文字仍以易讀 UI 字體為主。
- 整體維持緊湊、工具感、低裝飾，不做過度漸層、霓虹或大型插畫。

### 不直接照搬的部分

「照 Tunnel-Coding」是指視覺語言，不是把桌面 console 的互動原封不動搬到手機。

- PickPico 仍採手機尺寸適合的 touch target、spacing 與 navigation。
- 不把大量 log、raw endpoint、debug text 放回首頁。
- 不因為參考 console 風格而重新暴露已決定隱藏的工程細節。
- Home 仍以產品狀態與常用操作為核心，而不是工作紀錄串流。

簡化原則：

```text
Tunnel-Coding 的皮膚
+ PickPico 的手機資訊架構
= 同家族、不同裝置形態
```

## 2026-09-03 — Remote Lock belongs to Permissions / Capabilities

`Remote Lock` 不是連線或 Relay 設定。它代表的是：是否允許 Agent 使用 Android Device Admin 提供的「鎖定手機」能力。

UI 應把它放在 **Permissions / Capabilities** 類別，而不是首頁 Connection 區域。

建議以 toggle 呈現，例如：

```text
Lock phone                         [ ON ]
Allow agents to lock this device
```

或未授權時：

```text
Lock phone                        [ OFF ]
Requires Device Admin permission
```

### 語意原則

- toggle 控制的是「Agent 能不能使用這項能力」。
- 不應再使用容易被誤解成遠端安全機制的 `Remote Lock` 名稱。
- Android 實際仍可能需要跳轉系統頁面，由使用者完成 Device Admin 授權；toggle 是產品層入口，不代表 App 可以繞過系統授權流程。
- 這一類需要 Android 特殊權限的功能，後續應與其他 capability permissions 使用一致的呈現方式。

## 2026-09-03 — Appearance is a tunable glass spectrum, not one fixed skin

PickPico 不應只有固定的 VibeDeck 式磨砂玻璃。外觀設定需要能覆蓋兩個明確端點：

- **VibeDeck**：較厚、較柔和的 glass surface。
- **Tunnel / Clear Glass**：高透明、亮邊明顯、底色穿透強的清玻璃。

### Settings → Appearance

- Background 可使用 solid 或 gradient。
- Color A / Color B 必須提供可點擊的顏色選擇器；hex 色碼可以保留作為精確輸入，但不能是唯一操作方式。
- 提供 `VIBEDECK`、`CLEAR`、`TUNNEL` glass style presets。
- 同時保留連續調整：
  - `Transparency`
  - `Highlight`
  - `Background color intensity`
- 背景顏色調整必須肉眼明顯；卡片透明度不能高到把 palette 變化吃掉。
- 不提供名不副實的 blur 控制。原生 Android 版本目前使用清透材質、亮邊與 tint 層次，不假裝有 per-card backdrop blur。

簡化原則：

```text
VibeDeck frost  <------ adjustable ------>  Tunnel clear glass
```

## 2026-09-03 — Glass appearance follows VibeDeck, and is user-configurable

先前「參考 Tunnel-Coding」的方向保留其深色工具感與資訊密度，但**卡片材質改以 VibeDeck 現行 glass tokens 為直接視覺基準**。不能只做成黑底灰卡或高不透明度 panel。

### Glass surface

- 卡片需保留背景透出感，預設 glass density 參考 VibeDeck 約 7%。
- 表面加入低透明白色 145° 高光層。
- 使用細白邊、頂部 inner highlight 與柔和 shadow 建立玻璃厚度。
- 背景色彩必須能從玻璃後方看見；不能以接近不透明的深灰卡片遮掉背景。
- 綠／紅／藍等 semantic colors 僅作為狀態、操作與 accent，不作為整張卡片的不透明底色。

### Settings → Appearance

Appearance 是正式產品設定，不是 Developer option。需支援：

- Solid / Gradient background。
- 自訂 Color A / Color B。
- VibeDeck 系色盤 presets，例如 Nebula / Aurora / Ember / Midnight。
- Glass density 調整。
- 設定需持久化，Dashboard 與其他產品頁共用。

### Agent Inbox

Agent Inbox 是正式產品頁面，必須與 Dashboard 使用同一套背景與 glass component。

- 不得保留舊 Android 預設白／灰卡片 UI。
- Inbox message card、header、button、empty state 都要使用共用 appearance tokens。
- 從 Home 進入 Inbox 不應出現明顯的視覺世代切換。

