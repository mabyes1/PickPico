# PICO 行為測試基準 v1 實測報告

- runId：`PICO-BENCH-20260912-160610`
- 開始：2026-09-12 16:06:10（Asia/Taipei）
- 結果：完成前置確認，但手機操作被連接器前置錯誤阻塞；沒有把工具錯誤算成 PICO 功能通過或失敗。
- 模型欄位：`unknown (model not exposed)`
- 固定頁面本地暫存：`phone-data/index.html`、`phone-data/server.js`
- 固定頁面 SHA-256：`index.html` = `4C61808CCCBDE5F45728203B32DB40DB423E59AB44DC7DDAA5BCC72F25CB661F`；`server.js` = `621FDE99023FF187D8F6757050C23222BD1BCD51001941F5B1CC302027DD00E1`

## 前置實際狀態

成功讀到的 PICO 身分是 Samsung SM-S9110、Android 16/API 36、已安裝版本名稱 0.16.75；Relay 為 connected。此次本地報告保存的遠端端點是 `https://relay.pickpico.workers.dev/v3/nodes/wjLVc8x8sGvNvNRzjYOhtQ/mcp`，本地端點是 `http://192.168.0.15:8765/mcp`。版本名稱符合文件要求 0.16.75；`versionCode=112` 沒有由目前 `server_info` 直接提供，因此不冒充已確認。

直接讀到的本次政策是 Hyper Mode 開啟、核准模式 `yolo`；Android 本身的安全邊界仍適用。動態規格版本為 0.16.75，`capability.list` 實際回報 58 個能力，而文件對此版本預期為 60 個，故 G03 記為 `CONTRACT_CHANGED` 待檢視，不自行刪改測項。

## 為什麼沒有進入案例

`capability_status` 對 `node.info`、`phone.status`、`ui.inspect`、`app.list`、`workspace.info`、`node.start`、`workspace.write`、`process.exec`、`human.help`、`notification.list` 都指出需要 caller argument `agent`。但目前公開的 `pickpico.command_run` 介面只有 `commandId` 和 `arguments`，把 `agent` 放在 nested arguments、頂層額外欄位、caller、callerArguments、context 或 metadata 都沒有傳到手機端；每次均在手機執行前回覆 `agent is required`。

因此：固定頁沒有透過手機 Node.js 啟動，沒有 UI 觀察、通知、檔案、程序、Human Help 或故障注入結果。這是連接器／執行環境阻塞，不是 PICO 案例 FAIL；G01 的錯誤呼叫也不是 CLIENT_REJECTED，不能冒充 PICO 防呆通過。

## 案例結果

| 類別 | 結果 |
|---|---:|
| PASS | 0 |
| FAIL | 0 |
| BLOCKED | 10 |
| NOT_OBSERVED | 0 |
| NOT_RUN | 0 |
| CONTRACT_CHANGED | 1（G03） |
| PASS／實際執行案例 | 0／0 |
| PASS／全部 11 案例 | 0／11 |

B01–B08、G01、G02 均為 `BLOCKED`。G01 五個子項、G02 四個子項、G03 六個比較子項均未到達手機端；G03 另因實際能力數 58 對文件預期 60 記 `CONTRACT_CHANGED`。沒有任何案例可記 PASS，也沒有足夠手機證據可記 FAIL。

## 呼叫、重試與等待

- 本 run 的 PICO 連接器呼叫：41 次，明細見 `calls.jsonl`。
- 其中 `command_run` 嘗試：15 次；手機端實際執行：0 次；手機端成功案例操作：0 次。
- 同一路徑重試：12 次（server_info 2、policy_status 1、capability_search 2、command_run/node.info 7）；其餘是不同能力的首次探測。
- 正常案例呼叫：0；故障注入呼叫：0；人工等待：0；清理呼叫：0。
- 輸出原始 UTF-8 bytes、圖片／音訊 bytes、token：`unavailable`；本輪沒有取得手機畫面或媒體證據。

## 清理

本輪沒有成功啟動手機測試伺服器、程序或測試通知，也沒有改變手機狀態，因此沒有需要還原的手機項目。固定頁本地副本與證據刻意保留，未遞迴刪除。

## 重要問題與下一個獨立 run

1. `pickpico.command_run` 的公開連接器契約缺少／未轉送必要的 `agent` caller argument，導致連只讀手機操作都在執行前被擋住。
2. 固定測試頁無法進入手機本次資料夾，因此所有依賴固定頁的正常案例只能受阻，不能換頁或改用電腦瀏覽器補測。
3. 0.16.75 的 live capability list 回報 58 而基準預期 60；需要先確認是否為契約變更，再另開獨立 run 重新執行 G03 與全部案例。

需要複驗的案例：B01–B08、G01–G03 全部；本報告不重跑、不把本次阻塞後的結果改寫成成功。
