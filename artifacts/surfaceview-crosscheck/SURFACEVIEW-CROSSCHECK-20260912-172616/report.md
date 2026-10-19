# SurfaceView 無障礙交叉測試

本次 run：SURFACEVIEW-CROSSCHECK-20260912-172616。目標是確認 Samsung Internet 顯示的固定測試頁，是否實際向 PickPico 無障礙服務提供網頁元件。

## 結果

| 測項 | 結果 | 實際證據 |
|---|---|---|
| 裝置與版本 | PASS | Samsung SM-S9110、Android 16、PickPico 0.16.76、Relay connected |
| 固定頁部署與 Samsung Internet 開頁 | PASS | http://127.0.0.1:34153/，固定檔案雜湊見 observations.json |
| Samsung Internet ui.inspect 可見節點 | PASS（觀察完成） | 98 節點；完整樹無截斷；1 個全螢幕 android.view.SurfaceView |
| Samsung Internet includeInvisible=true | PASS（觀察完成） | 109 節點；2 個 SurfaceView；測試頁文字命中 0 |
| Samsung Internet 網頁元件可讀性 | FAIL | 標題、取件人欄位、儲存按鈕、公告等固定頁文字均未出現在節點；SurfaceView 沒有可用的網頁子節點 |
| Chrome 同頁比較 | BLOCKED | Chrome 已安裝且可啟動，但 url.open 走預設瀏覽器回到 Samsung；Chrome 地址欄的 ui.action／ui.type 回 STALE_OBSERVATION，沒有實際動作 |
| TalkBack | BLOCKED | 未修改設定。TalkBack 未出現在已安裝應用搜尋；安全設定查詢被 Android 拒絕；後續需本人解鎖，未收到回覆 |

## Samsung Internet 證據

前景套件為 com.sec.android.app.sbrowser，視窗標題為「瀏覽器」。可見樹中的 SurfaceView 路徑為：

w3786/0/0/0/0/0/1/0/0/0/0/0

它的 bounds 是 (0,0)-(1080,2340)，文字及 contentDescription 都是空白，actions 為 [4,8,64,16908342]。完整可見樹有 98 個節點，includeInvisible=true 有 109 個節點；兩種結果都沒有固定測試頁文字或網頁虛擬節點。畫面截圖路徑為 captures/screen-1789205403465.jpg。

截圖只證明畫面可見；節點結果沒有提供欄位或按鈕，因此沒有進行輸入或按鈕操作。

## 判斷

最可信的原因是 Samsung Internet 目前沒有把這個分頁的網頁內容暴露成無障礙虛擬節點；PickPico 讀到的是瀏覽器的外層 SurfaceView。PICO 的服務設定已包含 canRetrieveWindowContent、flagRetrieveInteractiveWindows、flagIncludeNotImportantViews 及 flagReportViewIds（見 app/src/main/res/xml/accessibility_service.xml），而且同一份樹已能深入讀到 Samsung Internet 自己的工具列元件。這使「單純是 PICO 遍歷太淺」的可能性降低，但因 Chrome 與 TalkBack 被環境阻塞，尚不能把責任完全隔離到 Samsung Internet。

本次沒有嘗試已廢除的 enhanced web accessibility flag，也沒有修改 PICO、建置或發布。

## 清理

已停止本次啟動的 Node 測試伺服器；沒有建立通知、沒有寫入測試頁狀態、沒有修改 TalkBack 或其他系統設定。TalkBack 保持未修改；Chrome／Samsung 的原有分頁未刪除。

需要另開獨立 run 的項目：Chrome 同網址完整 ui.inspect、Chrome 上一次無害欄位聚焦／輸入清回原狀，以及 TalkBack 原始狀態確認與 Samsung Internet 焦點觀察。
