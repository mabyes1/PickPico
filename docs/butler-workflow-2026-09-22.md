# 手機管家流程：2026-09-22 開發驗證

## 必須遵守的 Agent 操作流程

開始手機工作前建立 `task_create`。未結束任務持有持續亮屏，不因工具呼叫間隔或 Home/Pico 的 120 秒顯示租期而失效。Agent 必須在 finally/結束流程呼叫 `task_update`，狀態為 completed、failed 或 cancelled。多任務同時存在時，最後一個結束才釋放。

不修改系統螢幕逾時設定；移除 overlay/WakeLock 即恢復原有行為。使用者手動鎖屏仍被尊重。服務被停止或程序死亡會釋放系統資源。Agent 若直接消失而未關閉任務，亮屏會持續到任務被取消或節點停止；這是無閒置逾時要求的明確取捨。

`server_info.screenAwake` 回報 activeTasks/requested/overlayHeld/wakeLockHeld。requested 是意圖，不能替代實際螢幕驗證。

## 通知批次清除

透過既有 `command_run`：

```json
{"commandId":"notification.dismiss","arguments":{"all":true,"requestId":"cleanup_unique_id_20260922"},"agent":"actual model name"}
```

先讀取最多 200 筆（排除本 App），若超限則不執行。保存精簡通知資料後，對快照中的 clearable keys 呼叫 Android 批次清除。文字最多 600 字元，超出會標記 textTruncated。私有工作區 `notification-reports/<requestId>.json` 保存清除前摘要與驗證結果，不進 Git。

遺失回覆時重用同一 requestId，只讀回既有紀錄並觀察剩餘項目，不再次清除。prepared 表示紀錄已寫下但送出狀態不確定，應先觀察；不能把它當清除成功。confirmedAbsentCount 表示目前看不到，不保證每一筆皆由本次操作移除。新到的其他 key 不納入此次重試。

摘要紀錄包含個人通知內容，保留在使用者私有工作區，可由 workspace 工具讀取；目前不自動刪除。

## 連線診斷及恢復

`server_info.connectionDiagnostics`：電源互動/Doze/省電/電池最佳化豁免/背景網路限制/網路驗證狀態、最近斷線事件與當時狀態、最多三筆 Android 歷史程序退出原因。觀察與歷史退出紀錄不直接證明本次失聯的原因。

主節點正常啟動後回傳 START_STICKY。系統重建時，僅在 desiredRunning=true 下讀回憑證並恢復基本節點，不在背景重建相機／麥克風前景服務。使用者明確停止仍不重啟。尚未加入開機啟動、FCM 或 Samsung 設定自動修改。

Relay 原始碼新增標準 MCP tools/call 錯誤結果：no_device_connection 或 heartbeat_stale、心跳年齡、commandDispatched=false。不猜測程序死亡；實際對外行為須在 Relay 部署後驗證。

## 驗證紀錄

- Android：205 tests，0 failures，0 errors；debug APK 建置成功。
- Relay：7 tests 通過。
- 測試版：0.16.81-dev / code 118，未公開發布。
- 既有 0.16.80 真機：使用系統清除全部，再清除 Gmail 剩餘項，通知清單重新讀取得 0（不含自身通知）。這不是新批次 API 的 E2E 證據。
- 真機已回報 0.16.81-dev。13:25:31 建立任務，至 13:27:47 約 135 秒未呼叫手機工具，server_info 仍為 interactive=true / activeTasks=1 / overlayHeld=true。
- 兩個任務並存：第一個完成後 activeTasks=1 / overlayHeld=true；最後一個完成後 activeTasks=0 / overlayHeld=false / wakeLockHeld=false。
- 新批次 API 真機：4 筆快照中 2 筆可清除 Gmail 通知確認消失，2 筆不可清除的系統/日常行程通知保留；同 requestId 重播回傳 replayed=true，沒有再次清除。
- 新診斷真機：ignoringBatteryOptimizations=false；程序歷史退出資料可取得。這不是背景斷線根因或長時間待機修復的證明。
- 最後補上服務重建時共用任務 runtime，避免內部 HTTP server 重建後遺失任務 ID；重新通過 Android 測試與建置。
- 最終 APK 已安裝，13:30:19 程序重建後恢復 Relay；手機 base.apk SHA-256 與本機相同：`f68ef008bafcc9f7a99a1aa638b63b7d813801895bd405f1cf71e8248e832116`。
- 13:31:14 收尾：activeTasks=0、requested=false、overlayHeld=false、wakeLockHeld=false。最後通知查詢為 2 筆不可清除通知，clearable=0（排除自身通知）。
- 同版本重裝時既有 app.update_status 可能僅依版本碼過早標示 installed；本輪以程序重建與手機 base.apk hash 核實，不採信該狀態作唯一證據。
- Relay 離線錯誤改善僅完成原始碼與單元測試，尚未部署公開 Worker。長時間鎖屏恢復、系統回收重建與內部 HTTP 重建仍需專項真機測試。
