# OpenAI Tunnel 連線試驗

這是尚未完成端到端驗證的實驗流程。原本的 PickPico Relay 繼續保留。

目前先測這條路：ChatGPT → OpenAI Secure MCP Tunnel → 電腦上的 tunnel-client → 同網路的 PickPico 手機。

電腦和手機都必須保持運作，而且電腦要連得到手機。這個版本不代表 Android 已內建 Tunnel，也不代表手機切到行動網路後還能透過原本的區域網路 IP 連線。

## 準備

1. 從 [OpenAI Tunnel 設定頁](https://platform.openai.com/settings/organization/tunnels) 建立自己的 Tunnel，並關聯要使用的 ChatGPT 工作區。
2. 準備有 Tunnels Read + Use 權限的執行用 API key。建立或修改 Tunnel 另外需要 Read + Manage；ChatGPT 開發模式也有獨立權限。
3. 從設定頁或 [官方發行頁](https://github.com/openai/tunnel-client/releases/latest) 下載適合電腦的 tunnel-client。此腳本的參數依 Windows 版 0.0.14 查核。
4. 啟動手機 PickPico，在診斷頁取得 **Local MCP** 網址與 **Local bearer**。不要填 Remote MCP 網址，否則仍會繞經 Relay。

## 啟動

在專案目錄開 PowerShell，將下列佔位內容換成自己的資料：

```powershell
.\scripts\start-openai-tunnel.ps1 `
  -McpUrl 'http://<手機IP>:8765/mcp' `
  -TunnelId 'tunnel_<你的識別碼>' `
  -TunnelClient 'C:\你的工具目錄\tunnel-client.exe'
```

腳本會隱藏輸入 Local bearer 與 API key，不將它們寫入檔案或命令列參數。請在本機輸入，不要貼進聊天或 Git。執行時仍會存在程序記憶體與子程序環境中；結束時恢復原環境變數。

腳本先直接查詢手機工具清單，確認認證成功，再啟動官方客戶端。只想測電腦到手機時，可改用 `-CheckPhoneOnly`，不用 Tunnel ID 與 API key。

開啟 `http://127.0.0.1:18765/ui` 檢查連線。程式啟動、手機直連成功，都不等於 ChatGPT 已能呼叫手機。

## 驗收

1. 保持腳本執行，在 ChatGPT 開發模式新增連線，選 Tunnel，使用剛建立的識別碼。
2. 請 ChatGPT 執行 `server_info`，確認回覆是目標手機。
3. 執行 `command_run`，`commandId` 為 `phone.status`，`arguments` 為 `{}`。
4. 先查詢 `phone.notify` 的參數，再請 ChatGPT 發一則清楚標示「Tunnel 測試」的手機通知，實際查看手機有收到。
5. 用 Ctrl+C 停掉此腳本，確認這個測試連線不再能呼叫手機，排除誤用原本 Relay 的可能。

記錄上述結果後才能說此路徑跑通。Android 直接執行仍需另外驗證，不能把 Linux ARM64 發行檔直接當作 Android 支援證據。

官方目前將 Tunnel 用於私人連線與開發測試，不支援用它公開上架／散布外掛。其他 MCP 客戶端可繼續使用 PickPico Relay 或可互通的區域網路連線。

依據：[官方 Secure MCP Tunnel 文件](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)、[官方客戶端設定說明](https://github.com/openai/tunnel-client/blob/main/docs/configuration.md)。
