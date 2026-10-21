# PicoADB 授權失效與手動開啟修復

日期：2026-10-21。裝置：Samsung SM-S9110 / Android 16。

## 現場原因與恢復

- 原安裝版本 0.17.18，WRITE_SECURE_SETTINGS 權限仍在，PICO 本機 paired=true。
- 從 PICO UI 點「開啟開發者與 Wi-Fi ADB」，兩個 Android 開關成功開啟，NSD 找到 192.168.0.15:41059。
- PICO 連線失敗；其 native daemon 當次日誌明確顯示 `Post-handshake SSL_peek failed [SSLV3_ALERT_CERTIFICATE_UNKNOWN]`，LAN 與 loopback 都遭相同拒絕。
- 電腦用既有身分可連同一個端點。Android 無線偵錯的已配對裝置清單僅顯示電腦；PICO 的原金鑰檔案仍為 10/08 建立，並未遺失。
- Android `adb_allowed_connection_time=604800000`（7 天），PICO 上次成功連線記錄在 10/12。最符合證據的判斷為閒置授權到期；能直接確認的是 Android 已拒絕 PICO 身分，無法從目前狀態排除先前手動撤銷。
- 透過 Android 系統配對碼流程，以 PICO 原金鑰重新取得授權，PICO `picoadb.shell id` 成功回傳 `uid=2000(shell)`。沒有清除 App 資料、重建金鑰或停用系統授權逾時。

## 修正範圍

- PicoAdbClient：只讀本次連線後新增、最多 16 KiB 的 native log，端點匹配且出現 certificate-unknown 時回傳 `ADB_AUTHORIZATION_REQUIRED`。舊日誌、其他端點及缺少日誌不影響原始結果。
- PicoAdbManager：保留 paired 歷史紀錄，另外持久化 `authorizationRequired`；成功配對或確認已連線後清除。保留原始連線錯誤給 Agent。
- Dashboard：授權被拒絕時顯示「需要授權」，自動展開配對區，提供中英文恢復說明。
- picoadb.connect 指引 revision 5：區分本機配對紀錄與 Android 有效授權，說明重新授權條件。
- Android 版本 0.17.19 / versionCode 145；不調整全域 ADB 授權期限。

## 驗證

- Gradle 8.7 `:app:testDebugUnitTest` 指定 PicoAdbClientTest、PicoAdbManagerTest、DashboardPicoAdbStatusTest、DeveloperOptionsControlTest、OperationGuidesTest：共 31 項，0 failures / errors。另完成 `:app:assembleDebug`。
- `adb install -r` 成功，原裝與新 APK 簽章 SHA-256 相同；live capability schemaVersion=0.17.19。
- 原地更新前後 adbkey / adbkey.pub SHA-256 和檔案時間完全一致。未清資料。
- 新版 UI「關閉開發者與偵錯」已實際按下，三個 Android 開關均關閉。後續開啟測試遇到畫面切換與 busy；觀察到另一個 Muse Spark/1.3 正在操作。
- 依使用者選擇「Muse 還在使用，先保留目前狀態」，停止所有後續 UI 開關與配對測試。
- 最後讀取：開發者=true、Wi-Fi ADB=true、USB ADB=false、permissionGranted=true、authorizationRequired=false；connected=false，NSD 目前沒有端點。尚須在手機無並行操作者時驗證「開關已開但服務端點未出現」的狀態，不能把本次授權恢復或測試通過當成整體穩定性完成。
- 新版授權失效 UI 的自動展開與提示已由針對測試確認；尚未在實機故意撤銷授權重現新版提示。

APK SHA-256：`6D3044EA89E70B6CD487D46B278BD86CBA4CA56F35D92F071E77CEAEBF9ABECF`。

結論：授權被拒絕已確認並曾恢復到真實 shell；診斷與恢復提示修正已安裝。手動開關完整循環與剩餘服務端點問題仍待獨佔手機操作的實機驗收，本版不標記為穩定驗收完成。

## 0.17.20 後續修改（versionCode 146）

- Dashboard 系統操作頁合併為一張「開發模式設定」卡片：「開關 ADB」與「自動重新連線」兩個開關，加上「開啟無線偵錯設定」。移除「立即重新連線」按鈕；手動連線改為切換 ADB 開關。配對區只在未配對或 `authorizationRequired=true` 時顯示。
- 自動重新連線（`auto_reconnect`，預設開）：關閉時沿用現有連線，不探索也不重連，回傳 `ADB_AUTO_RECONNECT_DISABLED`。
- 使用者關閉無線偵錯（`adb_wifi_enabled=0`）時，即使 native transport 仍顯示 device 也不執行指令，回傳 `ADB_DISABLED`；關閉 ADB 時同時停止 NSD 探索。
- native adb 以 `ADB_MDNS_AUTO_CONNECT=0` 啟動，重連策略由 Java 端決定；升級後第一次執行會 `kill-server` 一次，替換保留舊環境的常駐 daemon（`native_autoconnect_disabled_v1`）。
- Samsung Android 16（SDK 36）：鎖屏後 USB 模式重設會讓 adbd 停止，因此開啟時一併開啟 USB 偵錯，驗證條件也包含 USB 偵錯；其他裝置行為不變。
- picoadb.connect 指引 revision 6：描述新卡片、自動重新連線語意與「尊重關閉設定」。

驗證：完整 `:app:testDebugUnitTest` 共 270 項，0 failures / errors；`:app:assembleDebug` 成功。0.17.20 尚未在實機做開關循環、鎖屏後保持連線與自動重新連線關閉的驗收，上一節的待驗項目仍未完成。
