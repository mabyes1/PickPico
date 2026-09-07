# PickPico 產品與能力說明

本文依 2026-09-05 的 Android 0.16.46 程式整理。檔名保留以維持既有連結，內容已改為目前功能說明，不再沿用早期 v0.1 的開發計畫。

## PickPico 負責什麼

PickPico 是 Android 上的 real-world Mobile Agent Node。外部 Agent 決定要做什麼，PickPico 把手機的感知、App 操作、執行環境與 HUMAN HELP 接進同一個工作流程，讓 Agent 能透過真實手機觀察環境、採取行動並在需要時向人求助。

目前 APK 支援 Android 8.0 以上的 ARM64 裝置，包含 Node.js 執行環境。沒有內建通用本地語言模型，也沒有 iOS 版本。Node.js 能執行程式，不等於手機已內建會自主思考的 Agent。

## 使用前先分清楚三件事

| 問題 | 由什麼決定 |
| --- | --- |
| 程式有沒有這項能力？ | 已登記的 command 與能力探索。 |
| 這支手機此刻能不能用？ | Hyper Mode、Android 權限、服務狀態、裝置與目標 App。 |
| 執行前會不會再問我？ | PickPico 核准模式、指令分類，以及 Android 或目標 App 自己的要求。 |

`capability_search` 或 `capability_status` 是檢查入口；`available` 不保證下一次呼叫一定成功。例如相機探索目前主要檢查相機權限，實際拍照時還會檢查相機前景服務是否啟用。

## 已登記的能力

下表表示程式已有實作，不表示每一項都在所有手機上驗證過。精確參數請查當下探索回傳的 `inputSchema`。

| 能力 | 主要指令 | 實際條件與限制 |
| --- | --- | --- |
| 裝置資訊 | `node.info`、`phone.status` | 查看手機與服務狀態。 |
| 相機 | `camera.capture` | 前／後鏡頭單張 JPEG；需要相機權限及相機前景服務，結果保存在 App 工作空間。 |
| 錄音、位置 | `microphone.record`、`location.get` | 需要對應權限與可用的系統服務；定位結果受訊號與裝置狀態影響。 |
| 語音、響鈴與音量 | `phone.speak`、`phone.ring`、`audio.status`、`audio.set` | 語音播報使用 Android TTS；音量與勿擾行為受系統狀態影響。 |
| 通知與人工協助 | `phone.notify`、`human.help`、`human.help.status` | 通知需能顯示；求助要有人回覆，或等到閒置期限到期。 |
| 喚醒與回桌面 | `phone.wake`、`phone.home` | `phone.home` 屬於 Hyper 能力；不等於解鎖安全鎖，回傳結果需確認是否仍要人操作。 |
| App 與連結 | `app.list`、`app.launch`、`url.open` | App 必須已安裝；連結需有可處理的 App。背景開啟不符合條件時改用通知／收件匣。 |
| 剪貼簿 | `clipboard.get`、`clipboard.set` | 受 Android 對背景剪貼簿存取的限制。 |
| 聯絡人 | `contacts.search`、`contacts.get` | 已實作查詢，需讀取聯絡人權限。 |
| 行事曆 | `calendar.list/get/create/update/delete` | 已實作讀寫，需對應權限、可用行事曆；寫入受核准模式控制。 |
| 檔案與照片選取 | `file.pick`、`media.pick` | 開啟 Android 選取器，由人選擇，再匯入 App 工作空間。 |
| 分享 | `share.send` | 開啟 Android 分享面板；人選擇目的地與收件人，回傳不代表已送達。 |
| 工作空間 | `workspace.info/list/read/write` | 位於 App 私有空間；`workspace.read/write` 是 UTF-8 文字操作，不是通用二進位附件介面。 |
| 執行程序 | `process.run/exec/output/stop` | 在 App 身分與沙盒限制內執行；不是 ADB shell，也不具備 Root。 |
| Node.js | `node.start/status/stop` | 啟動、查詢、停止手機上的 Node.js 工作；不保證被系統終止後自動恢復。 |
| 更新 | `app.update`、`app.update_check`、`app.update_latest`、`app.update_status` | 下載驗證後交 Android 安裝；安裝仍可能需要人確認。 |

## Hyper Mode 的能力

Hyper Mode 預設關閉。它是 PickPico 的功能開關，開啟後還要取得每項能力需要的 Android 權限。

| 能力 | 指令 | 需要什麼 |
| --- | --- | --- |
| 讀取介面元素 | `ui.inspect` | 啟用 PickPico 無障礙服務；只能取得目標 App 暴露的介面資訊。 |
| 點擊、輸入、捲動 | `ui.action`、`ui.type`、`ui.scroll` | 無障礙服務與可操作的目標元素；不是任意畫面都能可靠操作。 |
| 擷取螢幕 | `screen.capture` | 在 App 開啟 Screen Capture，並取得 Android MediaProjection 工作階段。 |
| 其他 App 的通知 | `notification.list/get/dismiss/actions/invoke_action/reply` | 通知存取權；回覆或按鈕必須由原通知提供。 |
| 鎖定手機 | `phone.lock` | 明確啟用裝置管理員功能。 |

安全鎖、指紋、PIN 與目標 App 的安全限制仍存在。Hyper Mode 不會自動授予權限或提供其他 App 私有資料存取。

側載 App 若被 Android 擋住無障礙設定，可能需要使用者先在 App 資訊頁允許受限制的設定，再啟用服務。是否出現該步驟，以手機實際畫面為準。

## 核准模式的精確行為

預設為 **Auto-approve**。模式在手機 App 設定，沒有供遠端 Agent 改寫模式的公開指令。

| 模式 | 程式現在如何判斷 |
| --- | --- |
| Ask Me | 對登記為 `sideEffect: true` 的指令要求核准；`human.help` 本身除外。不是所有讀取都會詢問。 |
| Auto-approve | 在有副作用的指令中，對 `security_action`、`software_update`、`arbitrary_process`、`filesystem_write`、`notification_write` 類別要求核准。 |
| YOLO | 不加 PickPico 的指令核准提示；Android、目標 App 與人工選取流程仍照常。 |

分類在程式中預先登記，並不是每次執行都由安全模型判斷。尤其 **Auto-approve 不會因為 UI 上出現付款、叫車或傳訊就自動辨識並攔下**。若需求是「送出前停下讓我確認」，Agent 的任務流程必須真的停下並求助，不能只依賴核准模式的名稱。

## HUMAN HELP 怎麼等待

Agent 可以提供問題、選項，並選擇允許文字回覆與圖片。圖片最多 3 張；使用者可以選圖或拍照。

`idleTimeoutSeconds` 可選 120、180、360 秒，預設 180 秒。這是可續時的閒置期限，使用者打字、選圖或相機活動會更新等待時間，因此總等待可能超過一次期限。

求助結果與圖片放在 App 私有儲存空間。求助呼叫會占用執行資源等待；這版不是完全非阻塞的人工等待架構。服務中斷、客戶端逾時或 Relay 斷線，都可能使原本的呼叫無法正常拿到回覆。

核准請求和 HUMAN HELP 共用相關手機互動流程，但意思不同：前者詢問是否允許一項指令，後者要求人補充資訊或完成實際動作。

## 照片、截圖與「使用者看得到」

`camera.capture`、`screen.capture` 可以回傳原生 MCP 圖片內容，並保留工作空間檔案路徑。但要分清楚：

1. 手機是否拍到或擷取到影像。
2. Agent 是否收到圖片內容。
3. MCP 客戶端是否把圖片顯示給使用者。

三者不是同一件事。2026-09-04 的實測中，前鏡頭拍照成功、Agent 能看到照片，但使用者在聊天畫面看不到圖片。因此不能把這條路徑描述成已驗證的「傳照片給使用者」。

`share.send` 可以交給 Android 分享面板處理；它不是直接開啟全螢幕看圖的指令，也不會自動替使用者選擇收件人。目前沒有通用的 `file.open` 或 `image.open` 能力；`url.open` 會拒絕 `file:`、`content:`、`data:` 等協定。

螢幕擷取需有效的系統分享工作階段。鎖屏或系統停止分享後，可能必須重新開啟並由人確認。

目前截圖結果附 `freshFrame` 與 `frameAgeMs`。沒有新 ImageReader 影格時，快取超過 5 秒就拒絕回傳；較新的快取會明確標示。影格經過的時間由 App 取得影格時計算，不能當成每個螢幕像素都已即時更新的保證。

## 長任務、背景執行與常亮

`task_create`、`task_update`、`task_status` 提供任務紀錄與狀態管理。目前最多保存 50 筆在記憶體中，服務重建後不保證保留。它們不是自主 Agent、持久排程器，也不會只因為建立任務就自動執行或在重啟後接續。

例如「一分鐘後倒數拍照」需要外部 Agent 或明確的執行流程串接等待、播報與拍照，不能把任務紀錄當作鬧鐘排程 API。

Node.js 與背景程序由 Android App 承載，仍受系統生命週期、記憶體、電量與網路影響。

常亮目前使用帶 `FLAG_KEEP_SCREEN_ON` 的小型 overlay，必要時回退到螢幕 WakeLock；相關視窗操作已移到主執行緒。指令期間與結束後有不同的保留時間，`phone.wake` 不延長 Agent 常亮租期。這是維持工作期間可見的措施，不是永不被系統終止的保活保證。

## 更新的邊界

APK 安裝前會檢查 SHA-256、套件名稱、簽章與版本，再交 Android 安裝器。下載網址目前接受 HTTP 與 HTTPS，並未強制 HTTPS；雜湊和簽章檢查也不代表 HTTP 本身經過加密。

更新檢查預設使用使用者已設定的 Relay 加上 `/v1/update/latest`；也可以明確提供 `manifestUrl`。沒有設定來源時會回報錯誤，不連到專案私人的服務。自架 Relay 若沒有發布更新資料，更新檢查不會有可安裝版本。

App 更新後，仍可能要重新啟動服務或重新確認螢幕分享。已安裝的測試 APK 版本，也不一定等於 Relay 更新端點目前發布的版本。

## 這次實測證明了什麼

2026-09-05、Samsung S23（SM-S9110）、Android 16、PickPico 0.16.46：

- 透過 Relay 查詢手機資訊、操作 PickPico 分頁，切頁後取得不同截圖。
- 後鏡頭連拍 3 次成功，前鏡頭單張成功。
- 約 100 秒等待測試結束後，手機回報亮著且未鎖定；不是所有省電設定或機型的長時間常亮驗證。
- 曾遇到有相機權限但相機前景服務未啟用；進入既有工程頁後才恢復拍照。
- 曾遇到螢幕分享停止，需人重新確認；聊天端圖片顯示未成功。

相機超時後的資源清理與過期截圖判斷已通過程式測試，但尚未在實機刻意重現延遲開相機與快取過期的故障。其他能力的程式存在，不應自動算進這份實測清單。

## 原始碼與相關文件

- [能力與參數、核准判斷](../app/src/main/java/com/mcpocket/poc/CommandRuntime.java)
- [能力狀態判斷](../app/src/main/java/com/mcpocket/poc/AndroidCapabilityRegistry.java)
- [模式預設值](../app/src/main/java/com/mcpocket/poc/McpocketPolicySettings.java)
- [任務紀錄](../app/src/main/java/com/mcpocket/poc/AgentTaskRuntime.java)
- [連線指南](remote-access-setup.md)
- [遠端傳輸與安全設計](remote-transport.md)

[回到 README](../README.md)
