# 已存的儀表板

使用者打開存好的儀表板時不會重新分析，直接看到存檔時的圖。卡片可以拖拉、縮放，版面自動存。可以移除單張圖、刪除整份儀表板。JEV 會在異常的卡片加上「需要關注」「嚴重警示」這類標註。

## Sub-features

- `reopen`：從清單或網址重開，重新整理後回到同一份，不打 `/api/jev/analyze`。
- `drag`：拖標題列移動卡片，版面存檔。
- `resize`：拉右下角縮放，版面存檔。
- `remove-card`：移除單張圖（有確認對話框）。
- `delete-dashboard`：刪除整份儀表板。
- `highlight`：異常標註跟 `/api/jev/highlight/<id>` 回應裡的 `highlights` 一一對應。本期重點見 [period-digest.md](./period-digest.md)。

## How to get to it (user POV)

- 導覽列「儀表板」→ 左邊「我的儀表板」清單點名稱。
- 直接開 `http://localhost:$FRONT/#/d/<id>`。

## Driving it with agent-browser

Preconditions:

- 有一份存好的儀表板，劇本用「E2E 銷售總覽」（S07 留下的）。
- session 已用 init script 開好並登入擁有者帳號。

- **從清單重開。** `$A open http://localhost:$FRONT/`、`$A find role button click --name "儀表板" --exact`、`$A find text "E2E 銷售總覽" click`、`$A wait --text "依據"`。`$A get url` 有 `#/d/<id>`。
- **重新整理不重新分析。** `$A reload`、`$A wait --fn "window.jev.seen.some((e) => e.url.includes('/api/jev/highlight/'))" --timeout 60000`、`$A eval "window.jev.seen.map((e) => e.method + ' ' + e.url + ' ' + e.status)"`：有 `/api/dashboards/<id>`、`/data`、`/api/jev/highlight/<id>`，沒有 `/api/jev/analyze`（S08-2）。
- **數字和標註。** `$A eval "window.jev.dashboard()"` 跟存檔前一樣（S08-3）。`$A eval "window.jev.last('/api/jev/highlight/').body.highlights"` 和 `$A eval "window.jev.cards()"` 的 `badge` 一一對應（S08-4）。截整頁 `$A screenshot --full "$RUN_DIR/S08-01-full.png"`。
- **拖拉。** 用 `eval` 呼叫 `jev.dragBy`，前後各讀一次版面：`$A eval "(async () => { const before = (await window.jev.dashboard()).layout; await window.jev.dragBy(document.querySelectorAll('.card-drag')[0], 0, 400); await new Promise((ok) => setTimeout(ok, 1500)); return { put: window.jev.seen.filter((e) => e.method === 'PUT').map((e) => e.url + ' ' + e.status), before, after: (await window.jev.dashboard()).layout } })()"`。要有 `PUT … 200`，第一張的 `gridPos` 變了（S08-5、S08-6）。
- **縮放。** 同樣寫法，元素換成 `document.querySelectorAll('.vgl-item__resizer')[0]`，位移 `200, 120`（S08-7）。`$A reload` 後 `gridPos` 還在（S08-8）。
- **移除、刪除。** S08-9～S08-12 照劇本，按鈕用 `$A snapshot -i` 找「移除這張圖」「刪除儀表板」的 ref，對話框按「取消」「移除」「刪除」。

## Gotchas

- 劇本要的是固定位移（往下 400px、縮放 200×120），所以用 `jev.dragBy`。agent-browser 的 `drag`（`$A drag '[data-chart-id="c2"] .card-drag' '[data-chart-id="c3"]'`）也拖得動、會送 `PUT`，但位移由目標元素決定，跟劇本的數字對不上。劇本 2.5 說拖不動，指的是 MCP 的 `drag`。
- 異常標註由 JEV 決定〔JEV〕。北區 6 月掉了 79%，通常至少會標一張；用到敏感欄位「數量」的卡片一定沒有標註。
- 製圖頁往下捲後，黏在上方的名稱列會蓋住卡片標題列。要點卡片上的按鈕，先 `$A eval "window.scrollTo(0, 0)"`。
- `agent-browser network requests` 在這裡抓不到請求，看請求用 `jev.seen`。
