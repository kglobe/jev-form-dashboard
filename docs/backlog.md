# 產品待辦清單

這次修復之後留下的構想、待辦和已知缺口，依類別整理，每項標優先度（P1 最先、P3 最後）和出處。

> **既有資料要注意**：這波修復上線後，下列舊資料可能要重新註冊或重新存檔，新規則才生效——網址帶帳密／token 的外部 API 資料源（之後每次抓取都會被擋下來）、補零代碼欄位被判斷成 `number` 型別的外部 API 資料源（值會變成 null；上傳的 CSV／Excel 存檔當下就正規化過，不受影響）、存了不合法卡片的舊儀表板（`/data` 會持續 500）。

## 功能構想

- P3 · 分析前先讓使用者用一段文字講想看什麼（一個輸入框／對話框），JEV 再照這個提示挑欄位、選圖表。（human 提出的構想）
- P3 · 存檔後用對話的方式說「幫我加一張圖」。JEV 目前只回答結構化的題目（choice / score / noul），沒有自由對話能力，這個功能要嘛在前面加一層「把需求轉成結構化題目」的邏輯，要嘛換一個支援對話的模型，需要另外設計，不是這次架構能直接做的事。（human 提出的構想）

## 正式上線前

- P1 · 用真正的登入驗證取代 `X-User-Id` header：現在誰都能改這個 header 看到、動到別人的儀表板，連空字串都會被當成合法使用者 `""`。（backend ledger，pre-flight／Task 5）
- P2 · `docker/postgres/Dockerfile` 還沒建立（這個開發環境沒裝 Docker），部署前要補。（spec §7）
- P2 · `DS_` 環境變數目前沒有綁定特定 host，同一組密鑰理論上能被任何允許清單內的外部 API 拿去用，正式串接前要重新檢視這個設計。（backend ledger，Task 6）
- P3 · 監控和告警：JEV 逾時、呼叫失敗、快取命中率這些現在只寫進 log，沒有任何監控或告警機制。

## 穩定度與效能

- P2 · 手動編出的卡片，把 `timeGrain` 指到不是日期型別的 `x` 欄位，`/data` 仍會回 500——卡片存檔驗證只查必填和型別合法性，沒有對照資料源 schema 檢查欄位真正的型別（`DashboardService.java` 的 `requireComplete`／`cardProblem`，錯誤來自 `TimeBucket.java` 的 `LocalDate.parse`）。（final-fix-backend-report.md「Concerns and open points」）
- P2 · 每次載入儀表板做異常檢查，都會重新讀一次資料源，沒有重用同一次 `/data` 已經抓過的資料。（backend ledger，pre-flight）
- P2 · analyze 要算資料筆數時用 `loaded.rows().size()`，外部 API 資料源等於要整批把資料抓回來才能算出筆數。（backend ledger，Task 9）
- P2 · `ChartCard.vue` 整包 `import 'echarts'`，主要輸出 chunk 高達 1.59 MB（gzip 後約 528 KB），沒有照實際用到的圖表類型做分包。（frontend ledger，Task 4）
- P2 · 異常檢查快取的「資料指紋」是所有沒用到敏感欄位的卡片 ChartData 雜湊值：改標題不會讓快取失效（太窄），沒被摘要的圖表型別、外部 API 資料順序不穩定都會讓快取失效（太寬），而且快取沒有上限也不會過期。（backend ledger，Task 11）
- P2 · 上傳解析整段包在 `@Transactional` 裡，解析大檔案時會一直占著資料庫連線。（backend ledger，Task 5）
- P2 · JEV 掛掉或連不上時，`JevClient` 和 `HighlightService` 每次都把整條 WARN stack trace 寫進 log；失敗又不快取，等於每次載入儀表板都重複噴一次。（backend ledger，Task 11）
- P3 · `bar`／`line`／`pie`／`radar`／`heatmap`／`stacked_bar` 的前端 `toOption` 目前忽略 mapping 參數，靠資料欄位的既定順序對應。（frontend ledger，Task 1、Task 2，plan 既有程式碼）
- P3 · heatmap 拿到空資料時 `visualMap` 會變成 0/0，這個邊界沒有實際驗證過。（frontend ledger，Task 2）
- P3 · 折線／長條圖沒有 `timeGrain` 時照數值高低排序；有 `timeGrain` 時，期別是照字串比較排序，不是照時間先後。（backend ledger，Task 4）
- P3 · 拖拉或縮放卡片後，`saveLayout` 在 grid-layout-plus 套用最終位置前就先讀了 `layout`；存檔後也沒把新版面同步回父層的 `config`。（frontend ledger，Task 3）
- P3 · `/data` 或 `/highlight` 哪天真的回了 204（目前的 `api.js` 對 204 一律回傳 `null`），`DashboardView.vue` 的 `found.map(...)` 會直接丟例外，打斷原本「查不到就安靜不標註」的設計。（frontend ledger，Task 3）
- P3 · `ChartCard` 只認得 `FIELD_MISSING` 這個錯誤代碼；卡片如果帶著其他 `error` 值回來，畫面上什麼提示都不會顯示，直接變成空白圖表。（frontend ledger，Task 3）
- P3 · `DatasourcePanel` 用同一個 `busy` 旗標擋上傳／註冊和逐欄位的敏感勾選，操作其中一個時其他都會被鎖住。（frontend ledger，Task 4）
- P3 · 分析預覽存檔失敗的錯誤訊息，取消對話框後不會清掉，下次打開還會看到舊的錯誤。（frontend ledger，Task 4）
- P3 · 切換儀表板（`App.vue` 的 `open()`）沒有防過期回應的機制，快速切換時舊的回應可能蓋掉新畫面。（frontend ledger，Task 4）
- P3 · PUT 更新儀表板時，body 裡的 `datasourceId` 會被完全忽略，換成別的資料源也不會報錯。（backend ledger，Task 10）
- P3 · 外部 API 回應是空 body（204 或空陣列）時目前回 502，訊息不夠精準。（backend ledger，Task 6）
- P3 · 外部 API 回應如果不是 UTF-8（例如 Big5/MS950），現在的串流 JSON 解析不會另外處理編碼。（backend ledger，Task 6）
- P3 · 舊版 `.xls`（非 `.xlsx`）還是用一次載入整本活頁簿的 POI API，大檔案一樣有吃滿記憶體的風險；這次只有 `.xlsx` 改成邊讀邊處理。（backend ledger，Task 5）
- P3 · `RestClientException` 全部歸類成「無法連線」，其實也包含非連線類的失敗（例如回應格式解析錯誤），訊息可能誤導。（backend ledger，Task 7）

## 資料品質

- P2 · 民國年日期（例如 `113/09/24`）和不帶分隔符號的 8 碼日期（例如 `20240924`）目前都不會被判斷成日期欄位：前者年份不是 4 碼，正則比對不到；後者會先被數字規則吃掉，變成 `NUMBER`。（`SchemaInference.java` 的 `DATE`／`NUMBER` pattern 與型別判斷順序）
- P3 · `distinctCount` 和樣本值都是拿原始字串去算，不是先照欄位型別正規化再算；同一個值的不同寫法（日期 `2024-1-1` 跟 `2024/01/01`，或代碼欄位補零與否）可能被當成兩個不同值。（backend ledger，Task 3）
- P3 · 非敏感但幾乎每筆都不同的欄位（例如備註），如果被選成 series 或 y，等於間接把接近逐筆的明細送給 JEV，目前沒有這類欄位的偵測或筆數上限，屬於設計層級的問題。（backend ledger，Task 11）

## 測試補強

- P3 · vitest 5 在 Node 23.9.0 上會印 EBADENGINE 警告，`package.json` 沒鎖 `engines`。（frontend ledger，Task 1）
- P3 · 幾支既有元件測試覆蓋不完整：`DashboardView` 的防過期 guard 只靠程式碼檢查過、沒有測試；`highlight` 只驗了第一個 guard，沒驗 error／highlight 兩個 guard 和「圖表先於標註顯示」以外的情境；勾選欄位的迴圈測試沒驗證數量；缺「已存檔模式可拖拉」這類正面對照組。（frontend ledger，Task 3、Task 4b）
- P3 · `api.test.js` 的 `rejects.toThrow` 是子字串比對、假設 `VITE_USER_ID` 沒設，`stubGlobal` 也沒有還原；測試檔沒有統一用 `enableAutoUnmount`，靠手動清空 `document.body` 頂著。（frontend ledger，Task 4b）
- P3 · 後端 round 2 沒有候選組合、候選欄位用滿全部欄位這兩種分支還沒有測試覆蓋；沒有測試驗證欄位改成敏感之後，已經快取的異常檢查結果會跟著失效。（backend ledger，Task 9、Task 11）
- P3 · 測試輸出裡還留著 Mockito self-attach 的 JDK 警告，和一段 JEV 500 情境印出的 WARN 加 stack trace，不影響結果但會洗版。（backend ledger，Task 4、Task 5、Task 9）
- P3 · `jev.dashboard` package 下有一個測試用的 controller，會被掃進所有 `@SpringBootTest`。（backend ledger，Task 1）
