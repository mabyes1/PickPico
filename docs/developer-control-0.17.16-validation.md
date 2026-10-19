# PickPico 一鍵開發模式 0.17.16

2026-10-12，Samsung S23 SM-S9110，Android 16 / API 36，versionCode 142。

## 使用方式

PICO → 設定 → 系統操作（ADB）→ 一鍵開發模式。

- 「開啟開發者與 Wi-Fi ADB」直接啟用開發人員選項與無線偵錯，再沿用原配對重新連線。
- 「關閉開發者與偵錯」關閉開發人員選項及 USB / Wi-Fi 偵錯，保留配對與 App 設定。
- 啟用時要求手機已解鎖。首次連入新的 Wi-Fi 網路，Android 仍可能要求網路允許確認。

首次使用需透過既有 ADB 授予 `WRITE_SECURE_SETTINGS`。本次實機已完成授權，日常開關不依賴 ADB 已連線。

```powershell
# 首次授權；先安裝 0.17.16 並建立一次 ADB 連線
.\scripts\Enable-PicoDeveloperControl.ps1

# 多裝置時指定目標
.\scripts\Enable-PicoDeveloperControl.ps1 -Serial '<ADB serial>'

# 撤銷 App 的直接控制權限；需要 ADB 已連線
.\scripts\Enable-PicoDeveloperControl.ps1 -Revoke
```

脚本僅處理固定套件的固定權限，使用 `dumpsys package` 核對實際授權，保留已有 ADB 身分與配對。

## 實機驗收

1. `adb install -r` 成功，手機服務回報 0.17.16；未清除 App 資料。
2. 授權腳本成功；App 原生 `Settings.Global` 讀回 `permissionGranted=true`。
3. PICO 畫面按「關閉」後，開發者、Wi-Fi ADB、USB ADB 皆為 false；電腦 `adb devices` 清空。
4. 保持 ADB 關閉，PICO 畫面按「開啟」後，開發者及 Wi-Fi ADB 為 true，原配對自動連到新的通訊埠。
5. 動態工具 `picoadb_set_enabled` 再完成一次 OFF → ON，`verified=true`、`pendingUserAction=false`。
6. 隨後 `picoadb_shell` 執行 `id` 成功，回傳 `uid=2000(shell)`。
7. 撤銷腳本核對權限已撤銷，再用 `picoadb_authorize_control` 透過已有 ADB 重新授權成功。
8. ADB 關閉時，`capability_status(picoadb.set_enabled)` 仍回報 available。手機 relay `tools/list` 已揭露兩個新工具；`guide.get(picoadb.connect)` 回傳 revision 4，包含首次授權、無 ADB 直接開關及保留配對流程。

本機連接器的快取 command enum 可能尚未更新；本次新工具使用手機 relay 的即時 JSON-RPC 介面完成驗收。即時手機工具目錄是判斷依據。

## 資料保留與收尾

私人備份留在 `C:\Users\ken\.codex\phone-care\2026-10-12`，沒有把私鑰或設定內容寫入 repository。

- ADB 私鑰、公鑰、known-hosts 檔案逐位元相符；配對欄位與原配對時間保留。
- 外觀、caller return、控制政策與藍牙停用設定相符。
- 比對期間只有 Pico 球垂直位置欄位出現小幅位移（0 → 0.01748634）；保留目前位置，不覆盖操作期間可能調整的位置。
- 操作期間暫時延長螢幕逾時，已讀回並恢復 15000 ms。
- 收尾保留一鍵控制權限，開發者 / Wi-Fi / USB 偵錯全部關閉。
- `activeTasks=0`、`activeOperations=0`、`requested=false`、`overlayHeld=false`、`wakeLockHeld=false`；BLE 搜尋仍停用。

## 建置與測試

- 完整單元測試：259 tests，0 failures。
- 指南 revision 更新後重跑 OperationGuidesTest：7 tests 通過；重新 assembleDebug 成功。
- DeveloperOptionsControlTest 覆蓋缺少權限不寫入、關閉後直接開回來、配對欄位保留及鎖定手機拒絕啟用。
- APK SHA-256：`2caf3517c4e12633b8e4dd8d8c7f7496a7ece4a71ab7b6e4b898e2f274cf3e85`。

驗收資料位於 `artifacts/developer-control-0.17.16-20261012/`：live-tools、live-guide、live-tool-off/on、live-authorize、live-shell、preserved-data-check 與 final-device-state。

## 技術參考與 Community Wisdom

[Android 官方開發者選項說明](https://developer.android.com/studio/debug/dev-options) 說明首次開發者啟用的系統流程。[DevSwitch 專案](https://github.com/retransmit/DevSwitch) 展示一次性 `WRITE_SECURE_SETTINGS` 授權後控制開發者及偵錯設定的做法；本次自行實作固定設定欄位，沒有引入該專案程式碼或相依套件。

DEV 社群的 [How to Enable Developer Options on Android: A Complete Walkthrough](https://dev.to/mohimoha/how-to-enable-developer-options-on-android-a-complete-walkthrough-54oh) 也整理首次啟用步驟。本次採用官方系統流程與實機驗證，既有配對金鑰持續沿用。
