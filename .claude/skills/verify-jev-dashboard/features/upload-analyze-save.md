# 上傳、分析、存成儀表板

使用者在「資料」上傳 CSV 或 Excel，欄位型別自動判斷；在「製圖」寫一句想看什麼，JEV 挑圖、出預覽；滿意就取名、存成儀表板，存完會切到儀表板頁。

## Sub-features

- `upload-csv`：上傳 CSV，欄位型別和筆數正確。
- `upload-excel`：Excel 預設讀第一個工作表，也可以指定工作表。
- `upload-errors`：沒選檔、找不到工作表、欄數不對、副檔名不支援。
- `analyze`：依需求分析，預覽卡片有「符合需求」，數字對得上。
- `adjust`：寫一句調整，需求紀錄變兩句。
- `save`：取名存檔，網址變 `#/d/<id>`。

## How to get to it (user POV)

- 「資料」→「新增來源」→「上傳檔案」分頁。
- 「資料」選來源 →「用這個來源製圖」，或導覽列「製圖」再選來源。
- 製圖頁的「分析」，或在「想看什麼？」框裡按 Ctrl+Enter。

## Driving it with agent-browser

Preconditions:

- Doctor 通過，session 已用 init script 開好並登入（劇本用 alice，跑到這裡之前要先做 S02、S03；只驗這個功能時用 `e2e-admin` 也可以，管理員多一個「外部 API」分頁）。
- 還沒有叫「銷售」的來源。

- **上傳。** `$A find role button click --name "資料" --exact`、`$A find role button click --name "新增來源"`、`$A wait --text "新增資料來源"`、`$A snapshot -i`。接著 `$A upload @<button "CSV / Excel" 的 ref> "<checkout>/docs/testing/fixtures/sales-h1.csv"`、`$A fill @<textbox "名稱（預設用檔名）" 的 ref> "銷售"`、`$A click @<對話框裡 button "上傳" 的 ref>`、`$A wait --text "6 個欄位 · 0 個敏感"`。
- **讀回來源。** `$A eval "window.jev.source('銷售')"`：`kind: "upload"`、`rowCount: 54`，型別照 S04-5。截欄位表 `S04-05.png`。
- **分析。** `$A find role button click --name "用這個來源製圖"`、`$A wait --text "圖表會出現在這裡"`、`$A snapshot -i`（「資料來源」是「銷售」，欄位標籤列出 6 個欄位）。`$A fill @<textbox "想看什麼？（選填）" 的 ref> "各區每月營收"`、`$A click @<button "分析" 的 ref>`、`$A wait --text "存成新儀表板" --timeout 60000`。
- **核對數字。** `$A eval "window.jev.preview()" > "$RUN_DIR/S07-05.json"`，照 S07-5 和劇本第 5 節的判斷規則比：`error` 空、`nulls` 0、依日期的 `x` 正好 2026-01～06、營收 321000、毛利 118975、數量 7221、明細 54 列。
- **截圖。** 整頁 `$A screenshot "$RUN_DIR/S07-04.png"`，每張卡片 `$A screenshot '[data-chart-id="c3"]' "$RUN_DIR/S07-06-c3.png"`，逐張用 Read 照劇本第 6 節看。
- **存檔。** `$A find label "儀表板名稱" fill "E2E 銷售總覽"`、`$A find role button click --name "存成新儀表板"`、`$A wait --text "依據"`、`$A get url` 是 `#/d/<uuid>`，`$A eval "window.jev.dashboard()"` 的數字跟預覽一樣。
- **錯誤和 Excel。** S04-3、S04-9～S04-13 照劇本做；S04-13 先 `cp docs/testing/fixtures/products.csv "$RUN_DIR/products.txt"`。

## Gotchas

- 沒跑 S05、S06 時，營收的顯示名稱是「營收」不是「營收（元）」，也沒有毛利率。S07-5 的 `dims`、S07-10 的毛利率要照實際狀態調。
- 有日期欄位時，預覽最上面一排是 KPI 卡（`type: "kpi"`），「第一張卡片」不一定是長條圖。
- 「新增資料來源」對話框打開時，snapshot 也會列出後面頁面的按鈕。`上傳`、`取消` 要用對話框裡那一顆的 ref。
- 預覽的 `jev.preview()` 讀的是 `jev.seen` 裡最後一次 `/api/jev/analyze`。init script 從頁面載入就開始記，按「分析」前不用再跑 helper。
- S07-7 的移除和復原要在同一個 `eval --stdin` 裡做完，提示只留 6 秒。
