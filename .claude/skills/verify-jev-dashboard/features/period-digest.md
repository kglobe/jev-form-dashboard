# 本期重點

已存的儀表板在篩選列下面有一塊「本期重點」，列出異常檢查標成「值得注意」以上的卡，照嚴重程度排，最多 3 張。每一筆是一顆按鈕：徽章、卡片標題、「2026-09 比 2026-08，南區 ▼ 18%」這樣的變化，按了捲到那張卡。檢查過但沒有需要注意的卡時，寫「本期沒有需要注意的變化（檢查了 N 張卡）」；JEV 失敗或沒有卡能檢查時整塊不顯示。

## Sub-features

- `digest-rank`：列的卡、順序、徽章跟 `/api/jev/highlight/<id>` 的回應對得上。
- `digest-text`：每一筆的期別、系列名稱和變化的寫法（跟 KPI 卡一樣）。
- `digest-empty`：檢查過、沒有需要注意的卡時的那句話和 N。
- `digest-scroll`：按一筆捲到那張卡，焦點移到卡片上。
- `digest-filter`：改篩選時跟著新的檢查結果變。
- `digest-remove`：拿掉列出的卡，那一筆馬上消失，不重新檢查。
- `digest-audience`：檢視者和訪客看得到，製圖頁預覽沒有。

## How to get to it (user POV)

- 打開已存的儀表板（要有用日期欄位的卡片），等異常標註出來，本期重點就在篩選列下面。

## Driving it with agent-browser

Preconditions:

- 有一份存好、有依月份卡片的儀表板。劇本用「E2E 銷售總覽」（S07），完整步驟在劇本 S25，要 S19、S20 留下的分享和預設篩選。
- session 已用 init script 開好並登入擁有者帳號。

- **等檢查結果。** 打開儀表板後 `$A wait --fn "window.jev.seen.some((e) => e.url.includes('/api/jev/highlight/'))" --timeout 60000`，再 `$A wait 500` 讓畫面更新。
- **列的卡和寫法。** `$A eval "window.jev.digest()"`：`entries` 跟 `expected` 的筆數、順序一樣，`badge` 是 `severity`，`title` 是卡片標題；`text` 照 S25-2 的規則比 `expected[n].change`。`expected` 是空的時看 `empty` 和 `checked` 的張數（S25-1、S25-2）。
- **截圖。** `$A screenshot '.dash-digest' "$RUN_DIR/S25-03-digest.png"` 和 `$A screenshot --full "$RUN_DIR/S25-03-full.png"`，用 Read 打開來看（S25-3）。
- **捲到卡片。** `$A snapshot -i` 找本期重點第一筆的 button ref，`$A click @eN`，`$A eval "document.activeElement.dataset.chartId"` 是那張卡（S25-4）。
- **篩選、拿掉卡片、檢視者、訪客、預覽。** 照劇本 S25-5～S25-9。篩選的下拉選單照 `filters.md` 的做法點。
- **窄螢幕。** `$A set viewport 390 844`、`$A screenshot '.dash-digest' "$RUN_DIR/S25-10-390.png"`、`$A eval "document.documentElement.scrollWidth"` 不大於 390（S25-10）。

## Gotchas

- 列哪幾張、什麼等級由 JEV 決定〔JEV〕。拿 `jev.digest()` 的 `expected` 比，不要寫死卡片。北區 6 月掉了 79%，只上傳 `sales-h1.csv` 時通常會列出幾張「嚴重警示」。
- `jev.digest()` 的 `expected` 讀的是 `jev.seen` 裡最後一次 `/highlight` 的回應。改篩選後要等新的回應進來再讀，不然比到的是舊的。
- 沒設 `TYPESAFE_API_KEY` 時 `/highlight` 回 204，本期重點整塊不顯示，這是對的，不是 FAIL。
- 本期重點要把焦點移到卡片上，所以卡片加了 `tabindex="-1"`；滑鼠點卡片也會拿到焦點，但只有鍵盤操作時才畫外框。
