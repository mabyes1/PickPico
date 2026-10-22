# PICO 行為測試基準 v1 實測報告

- runId：`PICO-BENCH-20260912-164007`
- 開始：2026-09-12 16:40:07（Asia/Taipei）
- 結果：更新後重新做前置實測；裝置身分與版本確認成功，但連接器在送出手機命令前拒絕 `agent`，本輪沒有進入任何案例。
- 固定頁面 SHA-256：`index.html` = `4C61808CCCBDE5F45728203B32DB40DB423E59AB44DC7DDAA5BCC72F25CB661F`；`server.js` = `621FDE99023FF187D8F6757050C23222BD1BCD51001941F5B1CC302027DD00E1`

## 前置確認

成功讀到：Samsung SM-S9110、Android 16/API 36、已安裝版本名稱 0.16.75、Relay connected。遠端端點與 Node ID 僅保留在本地執行證據；`versionCode=112` 未由 `server_info` 直接提供，不能冒充確認。

動態規格版本為 0.16.75；`capability_search`／`capability_list` 都回報 58 個能力，基準預期 60，因此 G03 記為 `CONTRACT_CHANGED` 待檢視。

## 阻塞證據

更新後 `task_create` 與 `command_run` 都要求 `agent`。文件指定的 `unknown (model not exposed)`、系統可辨識的 `GPT-5-Codex`、`Codex`，以及僅用於契約探測的字面值 `\\S`，均被連接器以錯誤 pattern `\\S` 拒絕。省略 `agent` 則回報 required。

因此沒有命令送到手機：沒有 `ui.inspect`、`phone.status`、固定頁啟動、通知、檔案、程序、Human Help 或故障注入結果。這是連接器契約阻塞，不是 PICO 功能 FAIL；G01 也不能把 CLIENT_REJECTED 算成 PICO 防呆通過。

## 結果

| 類別 | 數量 |
|---|---:|
| PASS | 0 |
| FAIL | 0 |
| BLOCKED | 10 |
| NOT_OBSERVED | 0 |
| NOT_RUN | 0 |
| CONTRACT_CHANGED | 1（G03） |
| PASS／實際執行案例 | 0／0 |
| PASS／全部案例 | 0／11 |

B01–B08、G01、G02 為 `BLOCKED`。G03 為 `CONTRACT_CHANGED`；其六個比較子項仍是 `BLOCKED`。沒有案例可記 PASS 或 FAIL。

## 呼叫與重試

- PICO 連接器呼叫：11 次；明細見 `calls.jsonl`。
- 真正手機命令：0 次；手機端執行：0 次。
- 重試：5 次（task_create 及 command_run 的 agent 契約重送）。
- 正常案例、故障注入、人工等待、清理：全部 0。
- 原始回覆 bytes、圖片／音訊 bytes、token：`unavailable`。

## 清理與後續

本輪沒有成功啟動手機伺服器、程序或測試通知，也沒有改變手機狀態；固定頁本地副本與證據保留。需要複驗的案例為 B01–B08、G01–G03 全部；本輪結果不改寫上一輪。

最重要的更新後問題是：PickPico 連接器的 `agent` 欄位驗證規則錯誤，導致真正的 `command_run` 無法派送。需修正工具 schema／caller argument 轉送後，才能進行下一個獨立 run。
