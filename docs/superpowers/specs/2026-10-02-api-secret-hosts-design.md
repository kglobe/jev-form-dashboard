# JEV 儀表板：外部 API 允許清單與密鑰綁定 host（設計）

日期：2026-10-02
前提：外部 API 資料源的規則見 `2026-09-23-jev-dashboard-design.md` 第 6.2 節，只有管理員能註冊外部 API 來源見 `2026-10-01-auth-sharing-design.md` 第 2.6 節。本文件只寫這次改變的部分。

## 1. 問題

backlog「正式上線前」有兩項 P2：

- 允許清單 `jev.datasource.allowed-hosts` 只能寫在 `application.yml`，或用命令列參數、`JEV_DATASOURCE_ALLOWED_HOSTS` 環境變數設定，`backend/.env` 沒有對應的 key。預設是空的，外部 API 來源一律抓不到，「E2E採購儀表板」打開時 `/data` 回 400「未開放註冊外部 API」。
- `DS_` 密鑰沒有綁定 host。header 寫 `${DS_任何名稱}`，就能把伺服器上任何一把密鑰送到允許清單裡的任何 host。2026-10-01 起只有管理員能註冊，但管理員註冊時還是哪一把都能用，送錯 host 等於把 ERP 的 token 交給另一個系統。

## 2. 允許清單改用 `.env` 設定

`application.yml`：

```yaml
jev:
  datasource:
    allowed-hosts: ${API_ALLOWED_HOSTS:}
```

- `backend/.env` 寫 `API_ALLOWED_HOSTS=erp.example.com,hr.example.com`。逗號分隔，前後空白不算，大小寫不分。沒設、留空，或只寫了逗號和空白，都是空清單，跟以前一樣不開放。
- 名稱刻意不用 `DS_` 開頭：`DS_` 開頭的設定都能寫進 header 送出去，允許清單不該在裡面。
- 命令列參數 `--jev.datasource.allowed-hosts=…` 和環境變數 `JEV_DATASOURCE_ALLOWED_HOSTS` 照舊有效，優先順序比 `.env` 高。
- 清單是空的時候，訊息改成「未開放註冊外部 API：請在 backend/.env 設定 API_ALLOWED_HOSTS」，一樣回 400。

## 3. `DS_` 密鑰綁定 host

### 3.1 設定

新增 `jev.datasource.secret-hosts`，型別是 `Map<String, List<String>>`：key 是 `DS_` 密鑰的名稱，value 是這把密鑰可以送去的 host。預設是空的，每一把密鑰都送不出去。

`backend/.env`：

```properties
DS_ERP_TOKEN=Bearer xxx
jev.datasource.secret-hosts.[DS_ERP_TOKEN]=erp.example.com,erp2.example.com
```

也可以寫在 `application.yml`：

```yaml
jev:
  datasource:
    secret-hosts:
      "[DS_ERP_TOKEN]": [erp.example.com, erp2.example.com]
```

- key 建議加方括號，Spring 只保證加了方括號的 key 原樣保留。
- host 逗號分隔，前後空白不算，大小寫不分。
- 同一把密鑰只寫一行。要多開放一個 host，就用逗號接在原本那行後面；再加一行 `[DS_ERP_TOKEN]=`，properties 檔同一個 key 只認最後一行，前一行綁的 host 就失效了。
- 不能用作業系統環境變數設定。環境變數名稱裡的 `_` 會被當成 `.`，key 跟著被拆開。只能寫在 `.env`、`application.yml`，或用命令列參數 `--jev.datasource.secret-hosts.[DS_ERP_TOKEN]=erp.example.com`。
- 密鑰的值照舊放在 `DS_ERP_TOKEN` 這個環境變數或 `.env` 裡，這次不改。

### 3.2 檢查

- header 的值 `${DS_X}` 要 `DS_X` 綁定了請求網址的 host 才放行，否則回 400「密鑰 DS_X 沒有開放給 <host>：請在 backend/.env 加上 jev.datasource.secret-hosts.[DS_X]=<host>」。`.env` 已經有 `[DS_X]=` 那一行時，照訊息加綁定要把 host 接在後面（見 3.1）。
- 先查完每一個 header 的綁定，才開始讀密鑰的值，沒開放的密鑰連讀都不讀。前一把有綁定但沒設定值、後一把沒綁定時，回後一把的 400，不會先回 500。
- 有好幾個 header 時，每一個都要通過。有一個沒綁定，整個請求就擋下，其他 header 也不會送出。
- 檢查放在 `HttpApiDataSourceProvider.resolveHeaders`。註冊時（`registerApi` 存檔前會先抓一次資料）和之後每次載入都會經過這裡。從 `.env` 拿掉綁定、重啟後端之後，已經註冊的來源下次載入就會被擋，不必刪掉重建。
- `secret-hosts` 不會讓 host 變得連得到。允許清單照舊先檢查，host 不在 `allowed-hosts` 裡，綁了密鑰也一樣回「host 不在允許清單」。
- 整體順序：網址檢查（`ApiUrlGuard`）→ header 格式（只能寫 `${DS_…}`）→ 綁定（每一個 header 都查完）→ 讀值（沒設定照舊回 500「環境變數 DS_X 未設定」）→ 發出請求。

`ponytail:` 綁定只在後端啟動時讀一次，改了 `.env` 要重啟才生效。要改成不必重啟，就每次從 `Environment` 重新綁定。

已經註冊的來源被擋下時，畫面照現有規則顯示：還有別的來源載入成功，用到它的卡片就顯示「資料來源「…」載入失敗，請稍後重新整理」；來源全部載入失敗時，`/data` 整份報錯，錯誤訊息取第一個失敗的來源，只有這一個來源時就是 400 和上面那句訊息。被分享的人照唯讀分享 spec 4.5，只看到「資料來源載入失敗，請聯絡儀表板擁有者」，看不到密鑰名稱和 host。卡片上的「請稍後重新整理」用在設定錯誤不太貼切，這次不改。

## 4. 畫面

「新增資料來源」的「外部 API」分頁（只有管理員看得到）裡，headers 欄位的提示改成：

> 範例：{"Authorization":"${DS_ERP_AUTH}"}。密鑰還要在 backend/.env 綁定這個網址的 host

標籤「headers（JSON，值只能寫 ${DS_環境變數名}）」不變。沒有新的 API。

## 5. 文件

- README「外部 API 資料源的規則」加上綁定規則，附一段可以直接複製的 `backend/.env` 範例，兩個設定都在裡面；`.env`、`application.yml` 的設定表和常見問題跟著改。
- `backend/.env.example` 加上 `API_ALLOWED_HOSTS=`，綁定的寫法放在註解裡。
- 唯讀分享 spec 決定表「`DS_` 密鑰共用」那一列指向本文件。

## 6. 測試

外部 API 來源在本機做不了 E2E（loopback 一律擋下），只能靠單元測試。

後端：

- 設定綁定（`JevPropertiesTest`，照正式啟動的順序把 `.env` 疊在 `application.yml` 上面）：沒設和 `API_ALLOWED_HOSTS=` 都是空清單；`API_ALLOWED_HOSTS=a.com, B.com` 拆成 `a.com`、`B.com`；`jev.datasource.secret-hosts.[DS_ERP_TOKEN]=erp.example.com, erp2.example.com` 的 key 還是 `DS_ERP_TOKEN`。
- `ApiUrlGuard`：空清單回新訊息，`API_ALLOWED_HOSTS=,` 綁定成的 `["", ""]` 和只有空白的 host 也算空清單；允許清單大小寫不分（用 `localhost` 驗證，不查 DNS）。
- `HttpApiDataSourceProvider`：有綁定就送；沒綁、綁到別的 host、綁的是別把密鑰，都回 400，而且沒讀密鑰的值；host 大小寫不分；兩個 header 有一個沒綁就整個擋下，沒綁的那把沒被讀；前一把有綁定但沒設定值、後一把沒綁定，回後一把的 400，兩把都沒讀；`fetchRows` 在發出請求前就擋下；綁了密鑰的 host 不在允許清單，照樣回「host 不在允許清單」。
- `DatasourceService`：有綁定時註冊成功，送出解開後的 header；拿掉綁定後，同一個來源載入和重新註冊都回 400，也沒有發出請求。
- `DatasourceControllerTest`：管理員註冊時帶了沒綁定的密鑰，回 400 和完整訊息。
- `application-test.yml` 把 `DS_JEV_TEST_UNSET` 綁到 `10.0.0.5`，`DashboardControllerTest` 才走得到「環境變數未設定」；yml 的方括號寫法也順便在完整的 Spring 設定裡驗證到。

前端：

- headers 欄位的提示有「密鑰還要在 backend/.env 綁定這個網址的 host」。

## 7. 決定（2026-10-02）

| 題目 | 選擇 | 沒選的 |
|---|---|---|
| 允許清單的變數名稱 | `API_ALLOWED_HOSTS` | `DS_` 開頭（會變成能塞進 header 的密鑰） |
| 允許清單格式 | 一行，逗號分隔 | yml 清單（`.env` 寫不了） |
| 密鑰和 host 的對應 | `jev.datasource.secret-hosts.[DS_X]=host1,host2` | 把 host 夾在 `DS_` 的值裡；每個 host 一個變數 |
| 檢查位置 | `resolveHeaders`，註冊和每次載入都檢查 | 只在註冊時檢查（拿掉綁定也擋不住已註冊的來源） |
| 檢查順序 | 先查完全部 header 的綁定，再讀值 | 讀了值再查；每個 header 查完綁定就讀值（前一把沒設定值會先回 500） |
| `secret-hosts` 和允許清單 | 各管各的，綁了 host 也要在允許清單裡 | 綁了就自動加進允許清單 |
| 作業系統環境變數 | `secret-hosts` 不支援，文件寫清楚 | 另外設計一種環境變數格式 |
| 前端 | 只改提示文字 | 在畫面上列出可用的密鑰 |
| 允許清單裡空白的 host | 不算，只有逗號或空白也是空清單 | 當成一個 host（訊息會變成「host 不在允許清單」） |
| 一把密鑰綁好幾個 host | 錯誤訊息不改，README 和本文件寫清楚要接在同一行 | 訊息改成「加上（已有這一行就接在後面）」 |
