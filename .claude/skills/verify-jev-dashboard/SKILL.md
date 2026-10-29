---
name: verify-jev-dashboard
description: 在真的 JEV 儀表板（Spring Boot 後端加 Vite 前端的網頁）上驗證使用者看得到的功能。先開一套跟使用者分開的環境（新 schema、8081／5181），再用 agent-browser 登入、上傳、分析、存儀表板、截圖存證。要證明改動在 App 裡真的能用、push 前要驗證、或要跑 docs/testing/e2e-playbook.md 的章節時用。
---

# verify-jev-dashboard

完整的回歸劇本在 `docs/testing/e2e-playbook.md`（下稱「劇本」）。劇本是寫給 Chrome MCP 的，步驟、帳號、預期數字都以劇本為準。這份只寫用 agent-browser 跑劇本時要換掉的部分。功能地圖在 `features/README.md`，先看那裡挑要驗的功能。

指令都在要測的 checkout 根目錄、用 Git Bash 跑。下面的 `$SKILL` 是 `.claude/skills/verify-jev-dashboard`。

## 不能碰

- 使用者自己的 8080、5180、9222（自動化 Chrome）和 schema `jev_dashboard`：不停、不 kill、不拿來測（劇本 2.1）。
- 主 checkout `C:/GitHub/jev-form-dashboard`：不在那裡 `git switch`，其他 agent 也在用。
- agent-browser 不加 `--auto-connect`、`--cdp`、`--profile`，也不跑 `close --all`。測 localhost 用它自己開的 headless Chrome；`close --all` 會關掉別的 agent 的 session。

## Launch

照劇本 S00（第 3 節）做，只有下面幾處不一樣：

1. 名稱和執行資料夾。Bash 工具每次呼叫都不留變數，所以把值寫進 `env.sh`，之後每個指令開頭用絕對路徑 `source`：

   ```bash
   RUN=$(date +%Y%m%d-%H%M); RUN_DIR="<scratchpad>/verify-runs/$RUN"; mkdir -p "$RUN_DIR"
   printf 'RUN=%s\nSCHEMA=jev_verify_%s\nRUN_DIR="%s"\nBACK=8081\nFRONT=5181\n' "$RUN" "${RUN/-/_}" "$RUN_DIR" > "$RUN_DIR/env.sh"
   ```

   S00-1 查到 8081 或 5181 有人在用，就把 `BACK`／`FRONT` 改成沒人用的 port。
2. S00-3 不用做。agent-browser 的 `upload` 可以直接讀 `docs/testing/fixtures/` 的絕對路徑。只有 S04-13 要 `products.txt`，那時再複製到 `$RUN_DIR/`。
3. S00-5 後端：在 `backend/` 用 `run_in_background` 跑劇本那行 `java -jar`，`<schema>` 換成 `$SCHEMA`，`8081` 換成 `$BACK`，最後加 `> "$RUN_DIR/backend.log" 2>&1`。要用 `java -jar`，不要用 `mvnw spring-boot:run`：Maven 另外分出來的 java 程序，TaskStop 停不掉。
4. S00-7 前端：在 `frontend/` 用 `run_in_background` 跑 `API_TARGET=http://127.0.0.1:$BACK node node_modules/vite/bin/vite.js --port $FRONT --strictPort > "$RUN_DIR/frontend.log" 2>&1`。Vite 的 log 夾著色碼，grep `Local:` 找不到，就緒與否用 curl 判斷。
5. 等就緒：一個 Bash 指令跑 until 迴圈，最多 180 秒，等 `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$BACK/api/auth/options` 和 `http://localhost:$FRONT/` 都回 `200`。後端要等 Flyway 建完表才會開始聽 port，所以 200 就代表能用了。逾時就看 `backend.log` 最後幾十行，回報後停下來。
6. S00-9 不用做，agent-browser 會自己開 Chrome。
7. 產生 helper 的 init script（見 Helpers）：`bash $SKILL/helpers/jev-init.sh "$RUN_DIR/jev-init.js"`。

## Doctor

開始操作前跑一次。之後只要畫面或數字看起來怪怪的，也先跑它：

```bash
bash $SKILL/helpers/doctor.sh $BACK $FRONT
```

它只讀不寫，檢查四件事：兩個 port 有人在聽（印出 PID）、jar 比 `backend/src` 新、前端 proxy 回 `{"oidc":null}`、經前端登得進 `e2e-admin`（這個帳號只有這次的新 schema 才有）。結束碼 0 才往下做。有 FAIL 就照它印的原因修，不要硬跑。

## Drive

**一個帳號一個 session。** 劇本的 `isolatedContext: "<帳號>"` 改成 `--session vjd-$RUN-<帳號>`（例如 `vjd-$RUN-alice`、`vjd-$RUN-anon`）。每個 session 是獨立的 Chrome，cookie 不互通。每個 session 的第一個指令要帶 init script，而且不能接 pipe 或導向檔案，不然背景 daemon 會卡住：

```bash
agent-browser --session vjd-$RUN-alice open --init-script "$RUN_DIR/jev-init.js" http://localhost:$FRONT
```

init script 讓每次載入、重新整理、`open` 之後都已經有 `window.jev`，App 的第一個 `/api` 請求也會記進 `jev.seen`。所以劇本裡「跑 helper」那步可以省掉，改成先確認 `agent-browser --session … eval "typeof window.jev"` 回 `"object"`。

劇本的 MCP 工具換成這些（`A="agent-browser --session vjd-$RUN-<帳號>"`）：

| 劇本 | agent-browser |
|---|---|
| `take_snapshot`、uid | `$A snapshot -i`，ref 寫成 `@e4`。頁面變了就重拍 |
| `fill` | `$A fill @e4 "值"`。它會先清空再輸入，所以劇本 2.5 說的「接在後面」不會發生，一般欄位不用 `jev.setField`。顯示名稱這類按 Enter 才存的欄位，`fill` 完照樣要 `$A press Enter` |
| 依標籤找欄位、按鈕 | `$A find label "儀表板名稱" fill "E2E 銷售總覽"`、`$A find role button click --name "儀表板" --exact`、`$A find text "E2E 銷售總覽" click`。`--name` 預設是子字串比對，「儀表板」也會對到「新增儀表板」，名稱短的按鈕一律加 `--exact` |
| Vuetify 下拉選單（「來源」「欄位」「期間」） | `$A click` 外層那個沒有名稱的 `combobox`，重拍 snapshot 會多出 `listbox` 和 `option "銷售"`，再 `$A click` 那個 option。`$A select` 會回 Done，但其實沒選上 |
| 拖拉卡片 | 劇本的位移用 `jev.dragBy`（見 `features/saved-dashboard.md`）。`$A drag '[data-chart-id="c2"] .card-drag' '[data-chart-id="c3"]'` 也拖得動，會送 `PUT`，這點跟 MCP 的 `drag` 不一樣 |
| `wait_for` | `$A wait --text "存成新儀表板" --timeout 60000`。預設只等 25 秒，分析要等 JEV，一律帶 `--timeout 60000` |
| `evaluate_script` 的 `async () => window.jev.xxx(…)` | `$A eval "window.jev.xxx(…)"`。會等 Promise 完成，再把結果印成 JSON |
| `evaluate_script` 的多行 function | 包成立即呼叫，用 heredoc 傳，見表格下方 |
| `upload_file` | `$A upload @e30 "<checkout>/docs/testing/fixtures/sales-h1.csv"`，`@e30` 是 snapshot 裡的 `button "CSV / Excel"` |
| `list_network_requests` | `$A eval "window.jev.seen.map((e) => e.method + ' ' + e.url + ' ' + e.status)"`。`agent-browser network requests` 在這裡什麼都抓不到 |
| `navigate_page` 重新整理 | `$A reload`，或 `$A open <網址>` |
| `press_key` | `$A press Enter`、`$A press Control+a` |
| `resize_page`、`emulate` viewport | `$A set viewport 1440 900`。X 軸標籤擠在一起時拉到 1900 寬（劇本第 6 節） |
| `take_screenshot` 帶 uid | `$A screenshot '[data-chart-id="c3"]' "$RUN_DIR/S07-06-c3.png"`，一張卡片截一張。`--full` 是整頁 |
| `list_console_messages` | `$A console`、`$A errors` |
| `close_page` | `$A close`，關掉整個 session |

劇本裡多行的 `evaluate_script`（例如 S07-7 的移除和復原），把 function 原封不動包成 `(…)()`：

```bash
cat <<'EOF' | agent-browser --session vjd-$RUN-alice eval --stdin
(async () => {
  window.scrollTo(0, 0)
  return window.jev.cards()
})()
EOF
```

`eval` 的輸出很長時（`jev.preview()`、`jev.dashboard()`），導向 `$RUN_DIR/<步驟>.json`，再用 `node -e` 抽要比對的欄位。只有 session 的第一個指令不能導向。

## Evidence

- 全部放 `$RUN_DIR`（`<scratchpad>/verify-runs/<RUN>/`）：截圖照劇本 2.4 命名（`S07-06-c3.png`），`eval` 的輸出存成 `S07-05.json`，加上 `backend.log`、`frontend.log`，以及照劇本第 9 節範本寫的 `results.md`。cleanup 不刪這個資料夾。
- 截圖照劇本第 6 節：存完一定要用 Read 打開來看，沒看過的那一步不能記 PASS。資料檢查通過、截圖有問題，一樣記 FAIL。
- 走使用者的路：上傳、分析、存檔、篩選這些都在畫面上操作。`jev.*` 只用來讀狀態、比對數字。劇本本身用 API 做的步驟（例如 S19-2、S21-22 的程式片段）例外。
- 改了東西就要從第二個地方讀回來確認：上傳後看 `jev.source`，存檔後重新整理再看 `jev.dashboard()`，拖拉後看 `jev.seen` 有沒有 `PUT`。
- 分析真的會打 JEV，不能 mock。標〔JEV〕的預期沒出現，記在備註，不算 FAIL（劇本第 1 節）。
- 只跑了某一節、不是從 S01 一路跑下來時，用的帳號和前面留下的狀態會跟劇本不同（例如沒跑 S05 就沒有「營收（元）」）。在 `results.md` 寫清楚，預期值照著調。

## Cleanup

1. 每個自己開的 session 跑一次 `agent-browser --session vjd-$RUN-<帳號> close`，再用 `agent-browser session list` 確認沒有 `vjd-$RUN-` 開頭的。別人的 session 不要動。
2. 用 TaskStop 停掉自己開的後端和前端背景工作，再跑 `netstat -ano | grep -E ":($BACK|$FRONT) .*LISTENING"`，要沒有輸出。停不掉就記下 PID 交給使用者，不要自己 taskkill（劇本 S99-2）。
3. 確認 8080、5180、9222 跟開始時一樣（劇本 S99-3）。
4. schema 留著不刪（劇本 S99），名稱寫進 `results.md`。
5. `ls "$RUN_DIR"`，截圖、JSON、log 都還在，才算收尾完成。

失敗的嘗試也要照這幾步收，不要留下程序和 port。

## Helpers

- `bash $SKILL/helpers/jev-init.sh <輸出的 .js>`：從劇本第 5 節抽出 `window.jev` helper，包成立即執行的 init script。劇本是唯一來源，helper 改了重跑一次就好，不要另外複製一份。
- `bash $SKILL/helpers/doctor.sh <後端 port> <前端 port>`：上面 Doctor 那段的檢查。

## 本機驗不到

- 外部 API 來源實際抓資料：`ApiUrlGuard` 擋 loopback，本機沒有能註冊的 API。S15、S16 只驗得到註冊時的權限和錯誤訊息；外部 API 分享後不露網址和 header，只有後端單元測試驗得到（劇本 S20）。
- 單一登入（OIDC）：本機沒有設定 issuer，只驗得到帳密登入。
- JEV 挑哪些圖、標不標異常，每次可能不同，所以有〔JEV〕標記。
