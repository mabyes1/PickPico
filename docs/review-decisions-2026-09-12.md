# PickPico 審查決策與修復（2026-09-12）

## 基準與使用者決策

基準為 `main @ bfd96844387b3842576d82df6810ce1f9d6a15b8` 加上開始修復時既有的未提交修改。
版本維持 `0.16.73 / versionCode 110`。沒有部署、發布或提交 commit。

| 項目 | 決策／目前狀態 |
| --- | --- |
| P1-01 Agent 操作自己的核准／政策介面 | 使用者明確接受，保留信任 Agent 的設計；不新增自身 UI 封鎖，也不變更核准政策。 |
| P1-02 BLE 裝置驗證與核准請求綁定 | 使用者知悉並暫緩，近期不打算使用韌體；本輪不修改 Android BLE 或韌體，風險並未修復。 |
| P1-03 換線後舊連線排隊命令仍執行 | 已修復本輪確認的排隊／轉送前取消漏洞，見下方界線。 |
| P1-04 未授權慢連線占用本地 HTTP worker | 先解釋，未授權修改；保留 4 個 worker、原本驗證順序、讀取逾時與 queue 設定。以單人、可信區域網路為前提，可降低至 P2 待辦；公開／多使用者情境另評估。 |
| P1-05 stdin 寫入阻塞發生在 timeout 之前 | 使用者在第二輪明確要求修復，已改為非同步 stdin 傳送與共用期限；詳見第二輪修復。 |
| P2-01 停止鈴聲清除其他排程 | 已修復。 |
| P2-02 執行失敗仍回傳 isError=false | 已修復已定義的操作結果分類與封裝。 |
| P2-03 workspace 覆寫破壞原檔 | 已修復一般覆寫；追加保留既有串流語意，見下方界線。 |
| P2-04 截圖逾時後晚到資源未及時清理 | 已修復。 |
| P2-05 Lint／CI 閘門 | 本輪未要求修復，未改動 CI 或 AgentAttention 的常數用法。截圖方法拆分時明確標記 API 30，該項原有 NewApi 告警已消失。 |

## 已實作修復

### P1-03：連線範圍的請求生命週期

`RelayClient` 的每個請求綁定原始 WebSocket、FutureTask、實際 OkHttp Call 及 App 內部有效性租約。
換線／停止時移除排隊工作、使租約失效並取消進行中的 HTTP 傳輸；HTTP Call 建立前後都會檢查請求是否仍有效。
舊連線的成功回覆或取消例外不會更新新連線健康狀態，也不會誤觸本地 MCP server 重啟。
本地 POST 關閉 OkHttp 自動連線失敗重試；真正的 loopback 傳輸錯誤標記 `executionState: unknown`。

`RelayRequestScope` 將有效性租約跨本地 HTTP 帶到命令執行執行緒。
因此，已轉送但仍在 `McpHttpServer` worker queue 的舊請求會回覆 HTTP 409，不呼叫操作。
`CommandRuntime` 在進入命令及人類核准返回後再次檢查，避免舊操作等到核准後繼續執行。
普通區域網路請求沒有 Relay 租約，不受換線取消影響。

界線：已跨入操作 handler 的副作用無法保證撤回。本輪未實作跨斷線的持久化 exactly-once、通用 MCP notifications/cancelled、或正式 Cloudflare Worker 的故障注入。
不能把「傳輸被取消」等同於「先前所有副作用都未發生」。Worker 原始碼沒有修改，這輪修復位於 Android App。

### P2-01：鈴聲專屬排程

`McpNodeService` 使用固定 `ringStopRunnable`。啟動與停止鈴聲只取消這個 Runnable，不再清空共用 Handler。
螢幕釋放、更新檢查與恢復排程保持獨立。

### P2-02：操作成敗與查詢成敗分開

`CommandOutcome` 統一分類明確錯誤、逾時及操作特定的失敗旗標；`CommandRuntime` 的歷史、Pulse 與 MCP 工具外層共用結果。
例如 captured=false、performed=false、process.exec 的逾時／非零結束碼不再被當成成功。
一般 false 狀態（例如 found、available、running、hasUpdate）不會一概判為錯誤。
成功查詢 node.status、process.output、command_status 的過去失敗紀錄，查詢本身維持成功；task_update 將任務標為 failed 也維持更新成功。

### P2-03：一般覆寫原子替換

`WorkspaceFileWriter` 在目標同目錄寫入唯一暫存檔，flush／sync 完成後才使用 ATOMIC_MOVE 替換。
不支援原子替換時直接失敗，不退回先刪除原檔的做法。支援 POSIX 的檔案系統保留原本權限。
固定數量的路徑鎖序列化 MCP 對同一路徑的覆寫與追加。父目錄並行建立成功不再被誤判失敗。

界線：append=true 保留串流追加成本與語意，不宣稱整次追加在程序崩潰時可回滾。
Shell／Node 的直接檔案寫入不參與 Java 的路徑鎖；也未新增 expectedHash／版本衝突保護。
本輪驗證一般 I/O 例外與並行寫入，沒有真機突然斷電測試。

### P2-04：截圖資源所有權

截圖從開始請求到轉換、編碼完成由 `CaptureResources` 管理 HardwareBuffer。
逾時、中斷及前期例外同樣釋放資源；callback 晚到時立即關閉 buffer。Bitmap 仍由轉換流程的 finally 清理。

## 第一輪驗證結果

| 檢查 | 結果 |
| --- | --- |
| Android 全套單元測試，強制重新執行 | 131 通過，0 失敗，0 錯誤，0 跳過；本輪增加 39 個測試。 |
| :app:assembleDebug | 成功完成 APK 編譯與打包；未安裝、未發布。 |
| :app:lintDebug | 未通過：1 個錯誤、91 個警告。剩餘錯誤為原有 AgentAttention.java:116 的 WrongConstant。 |

新增測試涵蓋：Relay queue 換線、轉送前競態、舊成功／失敗回覆、新連線可用、跨 HTTP queue 取消、人類核准等待期間換線、鈴聲排程隔離、錯誤旗標與歷史查詢、寫到一半失敗、讀者看不到半份覆寫、追加／覆寫鎖，以及截圖逾時／中斷／轉換失敗／資源競態。

## 待解釋項目的精確範圍

P1-04：上輪測試證明四條未授權不完整請求會暫時占滿處理名額，使第五個合法請求在 700 ms 內逾時；沒有證明四條連線能直接造成永久當機。
程式原有 10 秒 socket 讀取逾時，針對沒有新資料的讀取等待；它並非整個 HTTP 請求的絕對期限。
目前也沒有獨立的健康／取消處理容量；合法的多個長時間等待命令會占用相同名額。

P1-05 原始問題：流程為「啟動程序 → 同步寫完 stdin → 回傳背景 session 或進入 waitFor(timeoutMs)」。
子程序不讀 stdin 且管道塞滿時，呼叫卡在寫入，尚未到達有限時的 waitFor；background=true 也尚未回傳 sessionId。
觸發門檻取決於平台管道容量、輸入大小及子程序讀取行為，未在 Android 真機量測。
暫時可避免傳大型 stdin，改為先寫資料檔再讓命令自行讀檔；正式修復應將 stdin 傳送納入從程序啟動開始計算的共同期限。

## 第二輪修復：stdin 與強制模型型號

### P1-05

新增 `ProcessExecution` 管理 stdin 專用寫入執行緒與期限監督。期限在 ProcessBuilder.start 前以單調時間建立，程序群組識別等待、stdin 寫入／flush／close，以及前景程序等待共用剩餘時間，不會在完成輸入後重新計時。

背景模式不等待 stdin 寫完才交付 sessionId。仍在傳送的 stdin 受同一期限約束；輸入成功後保留既有背景服務的長時間運作語意，不把正常網站伺服器限制為 timeoutMs 後結束。輸入逾時或失敗會啟動程序群組清理，status 回傳 stdinState、stdinError、timedOut。

stdin 的 close 由寫入執行緒擁有。取消／逾時先終止程序群組，再有限等待清理，避免呼叫端嘗試關閉正在寫入的串流、反而卡在同一把鎖上。前景結果的 stdinError 會被 CommandOutcome 判為執行失敗；背景查詢則保留這些狀態讓 Agent 判讀。

界線：期限不等於硬即時回應保證；作業系統建立程序本身、排程延遲與既有程序群組清理寬限仍有成本。Android 真機的 signal／pipe 行為未在此輪實測。背景 timeoutMs 只約束啟動及輸入，正常長時間任務由 process.stop／節點停止管理。

### 模型型號必填

沿用 `agent` 欄位，在 `task_create`、`command_run` 與舊版直接裝置工具的 JSON Schema 及實際伺服器入口同時設為必填。缺漏、null、非字串、全空白／不可見字元與過長值皆拒絕，且在操作之前回覆明確修正訊息。能力探索、caller 登記與任務查詢／更新可在不重複填型號下使用；任務已保存的型號會延續。

單次命令透過 ThreadLocal 身分範圍將呼叫模型傳給 HomePulse、結果、歷史及 HUMAN HELP。每個並行命令各自持有型號，結束或例外時清除範圍，不拿其他任務的模型冒充目前呼叫者。多任務時，確定的目前命令型號不再被 UI 的人數摘要蓋掉。

此欄位為 Agent 自報，不能證明實際後端型號。工具說明要求填真實可知名稱／版本；若執行環境未提供，明填 `unknown (model not exposed)`，不虛構版本。現有客戶端需刷新工具結構；未帶 agent 的舊呼叫會被拒絕。尚未安裝或發布 APK，也未修改 P1-01 信任設計、BLE 韌體或 P1-04 容量設定。

### 第二輪最終驗證

| 檢查 | 結果 |
| --- | --- |
| Android 全套測試，強制重新執行 | 163 通過，0 失敗、0 錯誤、0 跳過。以最終工作樹測試結果為準。 |
| ProcessExecutionTest | 14 個測試，包含真實 JVM 子程序不讀 stdin、UTF-8 完整輸入與 EOF、共用期限、flush／close 阻塞、啟動期限已過、停止後子程序仍持有管道時的強制回收。 |
| ProcessExecServiceTest | 3 個服務層測試，驗證前景逾時、背景 session 及時取得並可停止、背景輸入逾時查詢。Android 與 ProcessBuilder 為受控 mock，未在手機執行命令。 |
| AgentIdentityTest | 13 個測試，涵蓋必填、型別／空白／格式字元、模型型號與 UI、並行隔離及兩種工具結構。另有真實本地 HTTP 的漏填拒絕測試。 |
| Relay socket-health 測試 | 5 通過。 |
| :app:assembleDebug | 完成；APK 位於 app/build/outputs/apk/debug/app-debug.apk。 |
| :app:lintDebug | 未通過；維持原有 1 個 WrongConstant 錯誤、91 個警告。AgentAttention.java:116 未修改。 |
| git diff --check | 通過；未提交 commit、未部署、未發布。 |

期限處理另保留明確的 stdin 狀態：已成功送完資料後停止背景服務，不將輸入改標成 cancelled；一般停止後若仍有子程序持有輸入管道，會升級強制終止程序群組。型號驗證也拒絕僅由不可見格式字元組成的字串。

### 第二輪最終驗證

| 檢查 | 結果 |
| --- | --- |
| Android 全套測試，強制重新執行 | 163 通過，0 失敗、0 錯誤、0 跳過；相較第一輪 131 個增加 32 個。 |
| 測試與打包期間原始碼指紋 | 前後相同，確認針對同一份原始碼完成驗證。 |
| :app:assembleDebug | 成功；另直接檢查 APK 內 DEX，確認包含 ProcessExecution 與 agent 必填驗證。 |
| Relay helper 測試 | 5 個通過。 |
| git diff --check | 通過。 |
| :app:lintDebug | 仍有原有 1 個 WrongConstant 錯誤及 91 個警告；未新增錯誤。 |

ProcessExecution 的 14 個測試涵蓋寫入／flush／close 阻塞、已過期啟動、前景共用期限、背景服務存活、取消與 SIGTERM 後仍占用 stdin 的清理升級，以及真實 JVM 子程序的未讀輸入和 UTF-8／EOF 傳送。另有 3 個服務層測試與 13 個模型身分驗證測試。它們不取代 Android 真機驗證。

取消後若父程序已退出但仍有子程序占住 stdin，清理會升級到強制終止群組。已經送完的 stdin 不會因日後停止背景服務而誤標為 cancelled。不可見格式控制字元不能單獨充當必填模型名稱。
