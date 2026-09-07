# Home V2 · Live Pulse

## 0.16.50 視覺修訂

球在 Android 13 以上以 `pulse-orb.agsl` 逐幀渲染：球面座標、雙層流場、細霧邊界與發光輪廓。主次色直接讀取 Theme；不再自行提高飽和度。Android 12 以下保留 Canvas 動畫作為相容方案。名稱與狀態分開排版，狀態獨立著色；短命令結束後顯示 Just finished，不假裝任務仍在執行。Agent 若有登記任務，首頁顯示回報的名稱，否則使用中性 Agent 標記。搜尋入口與初始化說明會引導 Agent 建立、更新任務。

首頁改為緊湊標題、光霧球、窄版兩行卡、最多三筆最近事件、橫向三欄摘要與單一橫向操作卡。左上角小球和選中導航顏色跟隨主題。

這次依使用者要求先處理畫面，只建置安裝包，未進行完整手機互動回歸。實際渲染與視覺效果仍需在安裝後確認。

首頁只回答現在是否有任務、正在做什麼、是否需要人介入與連線是否健康。

## 畫面

- 跟隨外觀主題色的原生柔光球，依待命、執行、等待、受阻與連線中呈現不同呼吸節奏。
- 短標題與兩行任務卡。工具沒有提供所屬 Agent 時顯示 Agent，不猜品牌或把無關呼叫串成任務链。
- 系統摘要包含目前狀態、Relay／本機連線、實際可用工具數。工具狀態在背景檢查，避免阻塞首頁動畫。
- 一次最多一张介入卡，人工回覆最優先；其次為啟動、任務問題、連線問題與可安裝更新。
- Activity 接住任務內容、備註與本次服務期間最近 50 次操作。既有訊息紀錄保留原行為。
- 節點停止控制移到 Settings 頂端；Home 停止時才提供 Start。

## 真實狀態

`HomePulse` 由命令開始／結束與任務建立／更新直接驅動。並行命令各自記錄，完成其中一個不會清掉另一個。服務重啟時清除執行中的快照，避免殘留忙碌狀態。最近完成的命令只以 Last 顯示，不能讓首頁一直顯示 Running。

任務是 Agent 主動回報的狀態，沒有回報 task 時只能顯示當下工具操作。短於畫面更新間隔的工具呼叫可能只顯示 Last。暫時性操作失敗提示保留 15 秒，完整資訊可在 Activity 查看。Task blocked 則依 Agent 的後續更新解除。

動畫在頁面離開、App 背景或系統關閉動畫時停止。大字體或較短螢幕可以垂直捲動，保留底部導航。

## 驗證

單元測試涵蓋完成後回到待命、並行命令、人工回覆優先、停止節點、任務身份與終止、失敗提示期限及連線中狀態。

真機驗收需另外確認：首頁的比例與換頁、Respond 開啟正確請求、Settings 可控制節點、切換主題後球跟隨顏色、Activity 可讀到任務與操作，以及返回首頁後動畫恢復。

## 0.16.51 — Shared visual language

Activity now uses Home's 68dp header, 66dp navigation shell, 20dp line icons and 8sp tab labels. Both hosts construct tab items through PulseNavigation. Activity entry and return suppress the platform slide animation. Content cards across Activity, Capabilities, Settings, appearance, connection, diagnostics and Human Help use the shared Home pulse surface with quieter borders and 12dp corners. Appearance opacity and highlight controls still adjust those surfaces. Settings actions and capability toggles follow the selected theme accents.

Validation: debug APK builds successfully. Device reported 0.16.50 before publication; the new installed appearance and page transitions have not yet been visually confirmed.
