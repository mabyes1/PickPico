# 手機省電與桌面整理實機紀錄

裝置：Samsung S23 / SM-S9110，Android 16 / API 36。
交付版本：PickPico 0.17.15 / versionCode 141，以 `adb install -r` 原地更新。
APK SHA-256：`4ce92d280ab2038ac33d8cf3a0fdfd03a19500e3be430e0d2203c0a6b08e0d58`。

## 已完成的改善

- 未使用的四按鍵支架 BLE 接收器改成明確啟用，預設關閉。設定頁「配件」提供開關和重新搜尋。開啟搜尋時採 LOW_POWER，每次 10 秒、間隔 30 秒、最多 3 次；找不到就停止，等待手動重新搜尋。
- 多步 Agent 任務在指令之間保持螢幕。操作或 task_update 續期 3 分鐘；完成、取消、失敗或閒置到期會釋放。單獨指令執行完即釋放，卡住的操作有 6 分鐘 watchdog。
- 螢幕保持只取得一次 WakeLock，續期由服務的一個計時器管理。實機曾出現定時 acquire 的提前釋放；最終版移除這個第二計時來源。系統原本的 15 秒螢幕逾時維持原值。
- UI 觀察比對排除非作用中、非焦點的 PICO 浮球視窗。浮球的就緒／工作中提示會隨每次工具呼叫變化；原本連這個提示也比對，會讓剛取得的 observationId 失效。PICO 主畫面、其他應用程式和權限對話框仍受原本的觀察保護。
- 動態 `tools/list` 中，`picoadb_status` 與 `command_run` 的描述仍直接揭露 `guide.get` / `guideId=picoadb.connect`。指南包含「PICO 設定 → 系統操作（ADB）→ 開啟無線偵錯設定」、使用現有配對重新連線及 `id` 驗證。

## 實機與建置驗證

- `testDebugUnitTest assembleDebug` 成功：256 tests，0 failures。
- Android package manager 回報 versionName 0.17.15、versionCode 141。
- 螢幕在 30 秒無操作後仍 interactive=true、requested=true、wakeLockHeld=true，接續 PICO 讀取畫面、開啟社群資料夾、確認 Threads、返回桌面成功。
- 解鎖或畫面動畫途中仍可能回傳 STALE_OBSERVATION；重新觀察穩定畫面後操作成功。
- 系統 Bluetooth 記錄 PICO started/stopped=12005/12005，從整理期間的多次擷取到最終版保持相同，沒有 ongoing scan；PICO 的 enabled/scanning/searchActive 都為 false。
- 更新前後，ADB 私鑰、公鑰、known hosts 檔案相同。配對欄位、權限政策、浮球位置、外觀與 caller return 設定也相同。私密備份和比對結果保存在本機 `C:\Users\ken\.codex\phone-care\2026-10-12`，沒有放進專案附件。
- 實機在最後操作後 160 秒仍持有螢幕鎖；停止操作後 249 秒的擷取確認 requested=false、wakeLockHeld=false、interactive=false。完成任務後 activeTasks=0、activeOperations=0、overlayHeld=false、wakeLockHeld=false。單元測試也驗證 180 秒閒置到期與續期跨越原期限。

## 桌面結果

首頁保留天氣、發票載具、桌布、計算機、日曆、媒體瀏覽器、瀏覽器、Spotify、三竹股市、MeowPtt 與 VibeDeck。ChatGPT 移到原本 Themes 的位置，底列加入 PICO 與社群與閱讀資料夾。LINE、電話、地圖、Chrome、相機的 Dock 保持原位。

第二頁整理成購物、金融與支付、Google、工作與工具、生活會員、智慧家庭及遊戲中心。Notion 和 Themes 移入工作與工具，Tuya 移入智慧家庭，Threads 和 Discord 加入社群與閱讀。

三竹股市、銀行、通訊及驗證通知與背景政策維持原設定。本輪沒有刪除個人檔案、卸載應用程式或重置電池統計。

## 耗電判讀

本次初始樣本從拔充電後約 2 小時 18 分取得：螢幕開啟約 1 小時 28 分、關閉約 50 分，分別耗掉約 24.5 與 2.5 個電量百分點。Threads、三竹、瀏覽器及 ChatGPT 佔主要前景使用。手機已開啟 Samsung 輕量效能與自動亮度。

可直接改善的背景活動是 PICO 無結果的 BLE 掃描：充電後樣本記錄 1 小時 44 分、378 次、0 結果。無效掃描已停止。新的日常待機耗電仍需下一段正常使用樣本；本次操作、安裝和亮屏測試期間不作為省電幅度估計。

## 參考

- [Android BLE 搜尋指南](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)：有限搜尋時間、找到目標即停止，避免持續循環掃描。
- [AOSP PowerManager 原始碼](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/os/PowerManager.java)：WakeLock 取得、釋放與逾時管理的標準實作。Samsung 實機行為以本輪驗收為準。

### 🌐 Community Wisdom: [Reverse Engineering a BLE Body Scale on Android: GATT Queues, Handshakes, and BIA Packets](https://dev.to/fedor-pasynkov/reverse-engineering-a-ble-body-scale-on-android-gatt-queues-handshakes-and-bia-packets-27eg)

> **Source**: [Fedor Pasynkov](https://dev.to/fedor-pasynkov)
>
> 作者的原始 BLE 實作調查提供連線狀態、搜尋停止和資源清理的實務參照。這次只找到兩篇相關案例，沒有評論反駁；屬於少量案例參考，實際決策由 Android 指南及手機上的掃描記錄支持。
>
> 🔗 [Read Full Discussion](https://dev.to/fedor-pasynkov/reverse-engineering-a-ble-body-scale-on-android-gatt-queues-handshakes-and-bia-packets-27eg)
