# PickPico Hackathon Demo Runbook

這份文件的目標只有一個：**現場不要靠臨場記憶。**

## Primary demo

主 Demo 應該在 2–3 分鐘內證明三件事：PickPico 是遠端 Agent node、能動態發現並使用手機能力、AI 遇到物理世界阻礙時能向人類求助後繼續。

### 0. 上場前

- PickPico node 已啟動。
- `RELAY STATUS = CONNECTED`。
- 手機與 Agent 端刻意不依賴同一個 LAN；可用 5G 作為最有辨識度的展示。
- Camera / microphone / location / notification access 依 Demo 需求事先授權。
- Android 音量、亮度與網路已確認。
- 先跑 `pwsh scripts/hackathon-readiness.ps1 -Device`。

### 1. 證明這是一個活的手機 Agent Node

Thin MCP Demo 優先讓 Agent 自己 discover，而不是把底層 capability 當 top-level tool 背給模型。例如使用者說「看看手機現在的狀態」，Agent 可先：

```text
capability_search("phone status battery network")
  -> phone.status
command_run("phone.status")
```

若使用者說「看看現在手機畫面」，則：

```text
capability_search("see current phone screen screenshot")
  -> screen.capture / ui.inspect
command_run("screen.capture")
```

一句話講法：**「Agent 一開始只看到穩定的 PickPico protocol；真正的手機能力是執行時動態發現的。」**

### 2. Sense / Interact

擇一或兩個即可，不要把 Demo 變工具型錄：

- `camera.capture`：看見現場。
- `screen.capture` + `ui.inspect`：像素 + semantic UI observation。
- `ui.action` / `ui.scroll`：跨 App 操作並再次 observation 驗證結果。
- `phone.speak` / `phone.notify`：Agent 對附近的人產生可見/可聽互動。

### 3. HUMAN HELP 核心橋段

使用 `command_run`：

```json
{
  "commandId": "human.help",
  "arguments": {
    "title": "幫我確認現場狀態",
    "instruction": "請看一下我面前的物品或面板。如果 AI 自己判斷不可靠，請直接拍一張照片並告訴我你看到什麼。",
    "actions": ["確認正常", "有問題"],
    "allowTextReply": true,
    "allowImages": true,
    "maxImages": 3,
    "idleTimeoutSeconds": 180
  }
}
```

手機跳出 HUMAN HELP 卡片後，現場人類完成一個 AI 不適合硬做的短操作，回文字、選項或照片。

一句話講法：**「AI 不必在做不到的地方無限重試，它有一個正式的人類出口。」**

### 4. Agent 繼續

收到 HUMAN HELP 結果後，Agent 必須再做一個後續動作，例如 `phone.notify` 或完成原本 workflow，證明 HUMAN HELP 不是獨立表單，而是真的把控制權交回 Agent。

### Thin MCP refusal regression

正式 Demo 前至少用實際參賽 Agent 跑以下語句，確認模型不會因 top-level tool list 很小而先說「我做不到」：

```text
幫我截一下現在手機畫面
幫我把這頁往下滑
幫我看看手機現在在哪裡
```

成功標準：模型在 capability 尚未知時主動呼叫 `capability_search`，找到對應 capability 後用 `command_run` 執行；不得在 discovery 前直接以「我是語言模型／無法存取手機」拒絕。

## Failure ladder

現場 Demo 的原則是：**失敗就降級，不在台上修 infrastructure。**

1. **Remote relay 正常**：跑完整跨網路 Demo。
2. **Relay 當下抖動**：若 Agent 與手機可切同 LAN，直接改 LAN MCP endpoint，故事仍成立。
3. **特定 sensor 權限失敗**：跳過該 sensor，保留 `phone.status` + `human.help` + `phone.notify`。
4. **Camera 不穩**：HUMAN HELP 用純文字 / action 回覆，不讓整個 Demo 被 camera 綁架。
5. **外部 Agent client 當機**：保留預錄 2 分鐘影片作為最後保險，但不要一開始就播放影片冒充 live demo。

## Three-run rule

在作品進入「可交」狀態前，同一套 Primary demo 必須 **連續成功 3 次**，中間至少包含一次 Wi-Fi ↔ 5G 切換。任何一次失敗都把計數歸零，先修根因再重跑。

記錄每次：

| Run | Remote | Sense | HUMAN HELP | Continue | Result |
| --- | --- | --- | --- | --- | --- |
| 1 |  |  |  |  |  |
| 2 |  |  |  |  |  |
| 3 |  |  |  |  |  |

## Optional Sponsor DLC: Financial Agent

**只有主 Demo、影片、公開 repo 與三連跑全部完成後才開始。**

最小情境：

```text
payment request
→ query price / balance / fee
→ prepare testnet stablecoin transaction
→ human.help approval
→ open existing wallet deep link
→ user signs in wallet
→ monitor tx / notification
→ continue workflow
```

禁止為了 Demo 把私鑰、seed phrase 或 custody 塞進 MCPocket。這個延伸的價值是 human-in-the-loop authorization，不是重新造一個 wallet。

