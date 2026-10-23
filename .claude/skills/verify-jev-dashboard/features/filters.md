# 儀表板篩選器

儀表板上方有一列篩選：「期間」（本月、上月、本季、上季、今年、去年）和最多 3 個分類 chip。篩選照顯示名稱對到不同來源的欄位；來源沒有那個欄位時，卡片下方寫「沒套用篩選」。擁有者可以「存成預設」，被分享的檢視者可以臨時改，但存不了。

## Sub-features

- `filter-period`：期間選單，篩完沒資料的卡片寫「篩選後沒有資料」。
- `filter-field`：「加篩選」選欄位和值，出現 chip；點 chip 可以改；chip 上的 ✕ 移除。
- `filter-label-match`：不同來源的欄位，顯示名稱一樣就併成同一個篩選。
- `filter-skipped`：卡片的來源沒有那個欄位時，照樣畫圖，下方加一行說明。
- `filter-clear`：「清除篩選」回到全部資料。
- `filter-default`：「存成預設」，重新整理後還在。
- `filter-viewer`：檢視者看得到預設、可以臨時改，沒有「存成預設」。

## How to get to it (user POV)

- 打開一份儀表板，「依據」下面、卡片上面那一列。

## Driving it with agent-browser

Preconditions:

- 劇本 S18 跑完（銷售有 2026-07，共 63 筆），「E2E 銷售總覽」已分享給 bob（S13-18）。只跑這一節時要先補這些，不然 S19-5 的數字（北區 158000）對不上。
- alice 和 bob 各一個用 init script 開的 session。

- **篩選列。** alice 打開「E2E 銷售總覽」，`$A snapshot -i` 有「期間」（顯示「不限」）、「加篩選」、停用的「存成預設」（S19-1）。
- **建分店業績來源。** S19-2 的程式片段用 `cat <<'EOF' | $A eval --stdin` 包成 `(async () => { … })()` 跑，再 `$A reload`。
- **加篩選。** `$A find role button click --name "加篩選"`，`$A snapshot -i` 找「欄位」「值」兩個下拉選單，點外層的 `combobox`，重拍 snapshot，點 `option "區域"`，「值」也一樣點 `option "北區"`，再按「套用」。`$A eval "window.jev.last('/filter-options').body"` 對 S19-3。
- **核對數字。** `$A eval "window.jev.dashboard(undefined, { period: null, fields: [{ field: '區域', values: ['北區'] }] })" > "$RUN_DIR/S19-05.json"`，照 S19-5 比。每張圖卡各截一張（S19-6）。
- **沒套用的篩選。** 換成「產品代碼：P01」，`$A eval "window.jev.cards()"` 只有分店業績那張的 `filterNote` 有字（S19-9、S19-10）。
- **期間。** 「期間」選「上季」，`jev.cards()` 裡退貨卡片的 `empty` 是「篩選後沒有資料」（S19-12、S19-13）。
- **存成預設和檢視者。** S19-14～S19-19 照劇本，bob 用自己的 session。
- **API 檢查。** S19-20 的程式片段一樣用 `eval --stdin` 跑。

## Gotchas

- 「上季」的預期以 2026-10-01～12-31 之間執行為準。別的日期要自己照劇本第 4 節算那一季有哪幾個月。
- 「期間」「欄位」「值」是 Vuetify 的下拉選單，不是原生 `<select>`。`$A select` 會回 Done，但其實沒選上。要點外層的 `combobox`，重拍 snapshot，再點 `option`（在「加 KPI 卡」實跑過）。選單關掉後 ref 會變。
- 期間和 chip 一改就會重抓 `/data` 和 `/api/jev/highlight`，讀數字前先 `$A wait --fn` 等 `jev.seen` 裡出現帶 `filters=` 的 `/data`。
- 這份檔的 agent-browser 操作還沒實跑過，第一次跑的人照實際情況修正這裡。
