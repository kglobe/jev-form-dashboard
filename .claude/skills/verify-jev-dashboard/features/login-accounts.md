# 登入和帳號

使用者用帳號密碼登入，帳號名稱不分大小寫，密碼錯時看不出帳號存不存在。管理員可以建帳號、停用、設成管理員、重設密碼；權限改了，對方下一次操作會跳「請重新登入」。每個人都能改自己的密碼，改完要重新登入。

## Sub-features

- `login`：登入成功、密碼錯、帳號不存在、大寫帳號。
- `logout`：登出回到登入頁，`/api/auth/me` 變 401。
- `accounts`：建帳號、輸入檢查、停用與啟用、設成或取消管理員、重設密碼（只有管理員有）。
- `relogin`：權限或密碼被改之後的「請重新登入」對話框。
- `change-password`：改自己的密碼，改完重新登入。

## How to get to it (user POV)

- 開 `http://localhost:$FRONT`，沒登入就是登入頁。
- 頂部「帳號」選單：「改密碼」「帳號管理」（管理員才有）「登出」。
- 快捷鍵 Alt+1／2／3 切「資料」「製圖」「儀表板」。

## Driving it with agent-browser

Preconditions:

- Doctor 通過。這個功能要兩個以上的 session：`vjd-$RUN-e2e-admin` 操作，`vjd-$RUN-alice`、`vjd-$RUN-bob`、`vjd-$RUN-dave` 當被操作的人，`vjd-$RUN-anon` 測沒登入。每個都用 init script 開。

- **登入頁。** `$A snapshot -i` 有 `heading "JEV 儀表板"`、`textbox "帳號"`、`textbox "密碼"`、`button "登入"`，沒有「用公司帳號登入」（S01-1）。
- **密碼錯。** `$A fill @<帳號> "e2e-admin"`、`$A fill @<密碼> "wrong-pass-1"`、`$A click @<登入>`、`$A wait --text "帳號或密碼錯誤"`（S01-2）。
- **登入。** `$A fill @<密碼> "E2eAdmin-2026"`（`fill` 會先清空，不用 `jev.setField`）、`$A click @<登入>`、`$A wait --text "還沒有儀表板"`。`$A eval "window.jev.api('GET', '/api/auth/me')"` 回 200 和 `"username": "e2e-admin"`（S01-5、S01-6）。
- **帳號選單和登出。** `$A find role button click --name "帳號"`、`$A snapshot -i` 對 S01-7 的選單項目，`$A find text "登出" click` 後回到登入頁（S01-8）。
- **帳號管理。** e2e-admin 的 session 照 S02 建 alice、bob、carol、dave，表格和錯誤訊息照 S02-1～S02-8。被操作的人各用自己的 session 登入（S02-10、S02-13、S02-16）。
- **請重新登入。** e2e-admin 按 dave 那列的「設成管理員」後，dave 的 session `$A find role button click --name "資料"`，`$A wait --text "請重新登入"`，截圖 `S02-18.png`（S02-17～S02-19）。
- **改密碼。** alice 的 session「帳號」→「改密碼」，照 S03-1～S03-9。S03-3、S03-4 查有沒有送出請求，改看 `jev.seen` 裡的 `POST /api/auth/password`。

## Gotchas

- 管理員帳號是 `e2e-admin`，不是 `.env` 裡的 `ADMIN_USERNAME`。啟動指令的環境變數會蓋過 `.env`。
- 一個帳號一個 session。兩個帳號擠在同一個 session 會共用 cookie，檢查會假通過。
- 密碼錯太多次會鎖帳號（S17）。不要拿 `e2e-admin` 一直試錯；鎖定只測 carol。
- S02-19、S02-25 是已知問題（backlog 2026-10-02）：紅色的「登入逾時」提示還在。看到一樣的情況就記 FAIL 加備註。
- 選單、對話框關掉後，snapshot 的 ref 會變，每次操作前重拍。
