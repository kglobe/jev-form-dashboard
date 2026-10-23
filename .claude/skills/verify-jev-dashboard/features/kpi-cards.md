# KPI 大數字卡

有日期欄位的來源分析完，預覽最上面一排會放 KPI 卡：一個大數字（本期）和一行小字（跟上期比）。擁有者可以在儀表板上「加 KPI 卡」、用齒輪改設定和門檻；碰到門檻時大數字變紅。KPI 卡跟著儀表板的篩選重算。

## Sub-features

- `kpi-auto`：分析時自動放最多 3 張，排在第一排，每張 4×2。
- `kpi-add`：儀表板篩選列最右邊「加 KPI 卡」，沒有日期欄位的來源不能加。
- `kpi-settings`：「KPI 卡設定」改標題、門檻（低於／高於）、門檻值。
- `kpi-threshold`：碰到門檻時大數字和「低於／高於門檻 …」是紅色。
- `kpi-filter`：篩選和期間改了，數字跟著變。
- `kpi-viewer`：檢視者看得到，但沒有「設定」和「加 KPI 卡」。

## How to get to it (user POV)

- 「製圖」選有日期欄位的來源，按「分析」。
- 儀表板篩選列最右邊的「加 KPI 卡」。
- KPI 卡標題列 ✕ 左邊的齒輪「KPI 卡設定」。

## Driving it with agent-browser

Preconditions:

- 自動放 KPI 卡只要有銷售來源就能驗。S21-6 以後要照劇本跑完 S18～S20（銷售到 2026-07、「E2E 銷售總覽」分享給 bob、預設篩選「區域：北區」）。
- alice 和 bob 各一個用 init script 開的 session。

- **自動放。** 「製圖」選「銷售」，`$A find role button click --name "分析"`，`$A wait --text "存成新儀表板" --timeout 60000`。`$A eval "window.jev.last('/api/jev/analyze').body.layout.map((s) => [s.type, s.gridPos])"`：KPI 卡是 `kpi`，`gridPos` 依序是 `{x:0,y:0,w:4,h:2}`、`{x:4,…}`，其他圖的 `y` 從 2 開始（S21-2）。
- **卡片內容。** `$A eval "window.jev.cards()"` 的 `kpi` 欄照 S21-3 比；每張 KPI 卡各截一張 `$A screenshot '[data-chart-id="c1"]' "$RUN_DIR/S21-04-c1.png"`，照劇本第 6 節的 KPI 卡那列看。
- **加 KPI 卡。** 打開「E2E 銷售總覽」，`$A find role button click --name "加 KPI 卡"`、`$A wait --text "加入"`、`$A snapshot -i -s ".v-overlay--active"`（只看對話框）。「來源」是下拉選單：`$A click` 外層的 `combobox`，重拍 snapshot，`$A click` 那個 `option "銷售"`，`$A wait --text "期別"`，「數值」「加總方式」「日期欄位」「期別」「門檻」就會出現（S21-9）。「數值」「門檻」也照這個方法選。照 S21-7～S21-11 做，`jev.cards()` 對 `kpi` 和 `color`。
- **設定和門檻。** 毛利率那張 `$A snapshot -i` 找「KPI 卡設定」的 ref，照 S21-14～S21-17。改完 `$A reload` 再讀一次（S21-16）。
- **篩選後重算和檢視者。** S21-18～S21-21 照劇本，bob 用自己的 session。
- **API 檢查。** S21-22 的程式片段用 `cat <<'EOF' | $A eval --stdin` 包成 `(async () => { … })()` 跑。

## Gotchas

- 本期是資料裡最後一個已經結束的期。只上傳 `sales-h1.csv`（到 2026-06）時，本期是 2026-06，營收那張是「4.5萬」、小字「2026-06 · 比 2026-05 ▼ 30.1%」（2026-10-23 實跑看到的），不是劇本寫的 2026-07。
- 有幾張 KPI 卡、挑哪幾個數值由 JEV 決定〔JEV〕，最多 3 張。
- 門檻的紅色用 `getComputedStyle` 比：淺色主題 `rgb(190, 18, 60)`，深色 `rgb(251, 113, 133)`。一般顏色在淺色主題是 `rgb(18, 19, 21)`。
- 預覽的 KPI 卡沒有「設定」（`settings: false`），存成儀表板後擁有者才有。
