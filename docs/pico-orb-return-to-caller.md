# Pico 球：回到呼叫端

> 目前保留底層登記與返回能力，但 Pico 球小視窗隱藏此區塊。多數 AI 宿主無法可靠得知自己的 Android App 或精確返回畫面，而且懸浮球原本就顯示在目前 App 上方；現在顯示會造成錯誤期待。待呼叫端能提供可信來源後再啟用 UI。

## 呼叫端如何發現與使用

MCP 工具清單（包括 thin 模式）公開 `caller_register`，工具說明及 `task_create.callerId` 欄位會引導 AI 使用。名稱沿用既有工具的底線格式；它是直接 MCP 工具，不是 command_run 的指令。更新後需讓客戶端重新取得工具清單。

先呼叫 `caller_register`：

```json
{"name":"來源 App","type":"app","packageName":"com.example.app","returnUrl":"https://example.com/chat/123"}
```

收到 `callerId` 後，建立任務：

```json
{"objective":"處理手機上的工作","callerId":"填入剛取得的 callerId"}
```

電腦端且沒有手機可開的返回位置時，可只登記 `{"name":"電腦助理","type":"remote"}`。來源未知可省略登記，正常手機操作不受影響。登記無法自行發現來源，也不代表已驗證該 App 身分；呼叫端必須提供自己確知的資訊。

最多保留 50 筆登記，服務重啟或舊登記被淘汰後須重新登記。任務建立時複製返回資料，後續登記不會改動舊任務。無效 callerId 會明確報錯；不可同時傳 callerId 與 context.caller。

## 相容的直接填寫方式

小視窗新增「回到呼叫端」。返回資料放在 task_create 的 context.caller，跟隨該任務的更新，不讀取目前前景 App 來猜來源。

```json
{
  "objective": "處理手機上的工作",
  "context": {
    "caller": {
      "name": "來源 App",
      "type": "app",
      "packageName": "com.example.app",
      "returnUrl": "https://example.com/chat/123"
    }
  }
}
```

packageName 與 returnUrl 可擇一提供；同時提供時，用指定 App 開啟 HTTPS 連結。只提供套件名稱時回到該 App，不保證回到特定對話。連結只接受無帳密的 HTTPS 網址。

沒有可返回位置且 type 為 remote 時，顯示灰色「此呼叫來自遠端 · 請回原裝置繼續」；來源不明則顯示「未提供返回位置」。App 未安裝或開啟失敗時顯示提示。

獨立指令及求助請求目前沒有任務關聯，因此不借用其他任務的返回位置。多個有效任務並行時也不猜來源。返回資料的保留期間與既有任務相同，服務重啟後不保留。

現有呼叫端需要開始提供 context.caller 才能啟用此按鈕；未提供資料的舊版呼叫端維持灰色提示。
