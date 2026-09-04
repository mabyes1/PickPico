# PickPico 連線指南

本文依 Android 0.16.34 的程式與介面整理（2026-09-04）。說明目前可操作的設定流程；介面改版後，按鈕位置可能調整。

## 選擇連線方式

| 方式 | 適用情況 | 需要準備 |
| --- | --- | --- |
| 區域網路直連 | Agent 所在的電腦可以直接連到手機 IP | 手機與電腦間可互通的網路、Local MCP 網址與 Bearer token |
| 遠端連線 | Agent 在雲端，或與手機不在同一個網路 | 手機可上網、可用的 PickPico Relay、Remote MCP 網址 |

手機端服務必須保持運行。安裝 APK 本身不代表 Agent 已能連線；也不是每個 MCP 客戶端都支援相同的連線設定格式。

## 設定遠端連線

1. 打開 PickPico，從首頁的 **REMOTE ACCESS** 或 **SETTINGS → Remote Access** 進入設定。
2. 在 Relay 欄位填入 Relay 的基底網址。專案目前使用：

   ```text
   https://relay.pickpico.workers.dev
   ```

   這裡不要填手機的 `/v3/nodes/.../mcp` 完整網址，也不要填區域網路 IP。

3. 點 **SAVE RELAY**。若服務已在運行，選 **Restart** 套用；若尚未啟動，回首頁按 **START**。
4. 等待 **REMOTE ACCESS** 顯示 **CONNECTED**。
5. 點首頁的 **COPY CONNECTION**，將連線資訊貼到支援遠端 MCP 的客戶端。

遠端連線資訊的形式如下。以下是佔位範例，不是可直接使用的裝置網址：

```json
{
  "url": "https://relay.pickpico.workers.dev/v3/nodes/<你的裝置識別碼>/mcp",
  "authentication": "none"
}
```

`authentication: none` 表示客戶端不必另外提供登入或 Bearer header。**完整網址本身就是存取憑證**，持有它的人可以向這支手機提出指令，仍受手機上的權限與核准模式控制。

不同客戶端可能要求只貼網址，或把欄位放進自己的設定結構；App 複製的 JSON 是連線資料，不是所有客戶端通用的完整設定檔。

## 確認真的連上

先請 Agent 呼叫 `server_info`，再執行 `command_run`：

```json
{
  "commandId": "phone.status",
  "arguments": {}
}
```

應收到手機的裝置、電池、網路與服務資訊。這才證明 Agent 到手機的請求有走完；Relay 的 `/health` 正常，只代表 Relay 本身有回應。

接著依需求開啟相機、畫面操作等能力。遠端連線成功，不會自動授予 Android 權限。

## 設定區域網路直連

1. 確認電腦能連到手機目前的區域網路 IP。訪客 Wi-Fi 或裝置隔離可能阻擋互連。
2. 在 PickPico 首頁啟動服務。
3. 到 **SETTINGS → Developer / Diagnostics** 查看 **Local MCP** 與 **Local bearer**。
4. 在客戶端設定手機顯示的網址，以及 `Authorization: Bearer <token>`。

```json
{
  "url": "http://<手機區域網路IP>:8765/mcp",
  "headers": {
    "Authorization": "Bearer <手機顯示的token>"
  }
}
```

Local MCP 使用 HTTP，沒有 TLS 加密，請用在可信任的網路。手機 IP 可能因換網路而改變。

首頁 **COPY CONNECTION** 在已有 Remote MCP 網址時會優先複製遠端資訊，即使 Relay 當下斷線也不代表它會改選 Local。要指定直連，請查看診斷頁的 Local 欄位。

## 停用遠端連線

把 Relay 欄位清空、儲存，再重新啟動服務。Local MCP 仍可使用。

停止首頁的 Node 服務則會停止 Local 與 Relay 連線。停用遠端不等於更換裝置識別碼；重新啟用後不應假設舊網址已失效。

## 常見情況

| 畫面或結果 | 代表什麼、如何處理 |
| --- | --- |
| `NOT CONFIGURED` | 未設定 Relay；需要遠端連線時填入基底網址並重啟服務。 |
| `CONNECTING`、`VERIFYING` | 正在建立連線或等待心跳確認，尚不能當作可用。 |
| `CONNECTED` | 手機與 Relay 的心跳已確認；再用 `phone.status` 檢查 Agent 端完整路徑。 |
| `node_offline` | Relay 找不到健康的手機連線。確認 Node 已啟動、網路可用、網址是目前這支手機的。 |
| `node_delivery_timeout` | Relay 未及時收到手機收件確認。先查連線，別把它當成指令已完成。 |
| 指令逾時 | 可能在等待人、排隊或失去連線。先查執行狀態與實際結果，避免重複執行有副作用的動作。 |
| 能連線，但相機要求重啟 | 相機權限和相機前景服務是兩個條件。從手機 App 停止再啟動 Node；若仍異常，可進工程頁查看狀態。 |
| 螢幕擷取要求設定 | 在 **CAPABILITIES** 開啟 Hyper Mode，再開啟 **Screen Capture**，完成 Android 的螢幕分享確認。更新 App 不會自動跳出這個視窗。 |
| 鎖屏後無法繼續擷取 | 先解鎖並查看 Screen Capture 是否仍啟用；若工作階段已停止，需要重新確認螢幕分享。 |
| App 沒打開，只出現通知 | 系統或 PickPico 的前景啟動條件未滿足。解鎖後點通知完成接續操作。 |
| Agent 看得到照片，我卻看不到 | 拍照回傳與客戶端顯示圖片是不同環節。客戶端收到圖片內容，不保證會顯示成使用者可見附件。 |

Wi-Fi 與行動網路切換後，PickPico 會嘗試重新連線；不要把重新連上解讀成切換期間的指令一定成功或會自動重送。

## 使用自己的 Relay

目前配套是儲存庫內的 Cloudflare Worker + Durable Object。不是任意網址或一般 HTTP 代理都能使用。

部署設定含專案帳號與 KV 綁定，需要先換成自己帳號的資源，不能直接把現有設定當通用的一鍵安裝指令。維護細節見 [遠端傳輸與安全設計](remote-transport.md)。

[回到 README](../README.md)
