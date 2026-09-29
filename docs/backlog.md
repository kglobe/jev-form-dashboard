# 產品待辦清單

這次修復之後留下的構想、待辦和已知缺口，依類別整理，每項標優先度（P1 最先、P3 最後）和出處。

> **既有資料要注意**：這波修復上線後，下列舊資料可能要重新註冊或重新存檔，新規則才生效——網址帶帳密／token 的外部 API 資料源（之後每次抓取都會被擋下來）、補零代碼欄位被判斷成 `number` 型別的外部 API 資料源（值會變成 null；上傳的 CSV／Excel 存檔當下就正規化過，不受影響）、存了不合法卡片的舊儀表板（`/data` 會持續 500）。

## 下一步

多來源儀表板的 A（每張圖各自一個來源）和 B1（合併來源：疊加、查表）已經在 2026-09-28 合併進 main（`166cab4`），B2（對照）也在同一天合併。

- ~~P1 · 把 main 推上 GitHub。目前只在本機合併，還沒 `git push`。~~ 已完成：origin/main 是 `90ac52c`。
- ~~P1 · B2 對照：同一張圖上比兩個來源的數字，例如每月銷售額對退貨額。~~ 已完成：疊加多一個「對照」，長條圖和折線圖可以放兩個數值，見 spec `docs/superpowers/specs/2026-09-28-compare-datasource-design.md`。
- ~~P1 · B3 比率：計算指標＝分子÷分母，例如退貨率、達成率。每一組先各自加總再相除，任何來源都能加。~~ 已完成：資料頁可以加比率，折線圖、長條圖顯示成百分比，見 spec `docs/superpowers/specs/2026-09-28-ratio-metric-design.md`。之後可以接的項目在 spec 第 11 節。

## 功能構想

- ~~P3 · 分析前先讓使用者用一段文字講想看什麼（一個輸入框／對話框），JEV 再照這個提示挑欄位、選圖表。（human 提出的構想）~~ 已完成：分析前可以輸入需求，預覽裡也能「調整」，見 spec `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`。
- P3 · 已存儀表板的對話式修改：存檔後用一句話說「幫我加一張圖」。預覽裡的「調整」已經把需求轉成 JEV 的結構化題目（`rel_i`、`keep_<chartId>`，加上 `current`），這裡可以沿用同一套機制，入口和存檔流程另立 spec 設計。（human 提出的構想）
- ~~P3 · 淺色主題與切換：介面改成深色指揮中心風格後，只有深色一套（`frontend/src/styles/command-center.css`、圖表的 `command-dark.json`）。要加淺色，得另外定色盤、用 dataviz 的 `validate_palette.js` 驗圖表配色，再設計切換入口。（UI 改版，2026-09-27）~~ 已完成：導覽列最下面有深色／淺色切換鈕，選過的主題存在 localStorage。淺色圖表色盤驗證時，有三個顏色對白底的對比不到 3:1，改靠圖例和明細表辨識（dataviz 的 relief rule）。
- ~~P3 · 刪除確認對話框改成 Tactile 的 header／body／footer 結構，按鈕文案改成「保留」／「刪除儀表板」。（同上，第 2 節）~~ 已調整：確認框加上標題「刪除儀表板」，兩顆按鈕改成實心，刪除鈕用錯誤色；文案還是「取消」／「刪除」。（UI 改版）
- ~~P2 · 比率的期間對齊：分子、分母的資料期間不一樣時（例如對照來源上退貨只有 7～9 月、銷售有 1～9 月），依分類的比率會被沒有分子的期間稀釋。B3 的 E2E 看到「退貨率 依 product」只有 1.3%～1.8%，按月看卻是 3.5%～4.3%。可以考慮只算分子、分母都有資料的期間，或在對照來源上不讓比率配分類欄位。（B3 手動驗證，2026-09-29）~~ 已完成：比率只算分子、分母都有資料的月份，圖下方寫出算的是哪幾個月，見 spec `docs/superpowers/specs/2026-09-29-ratio-period-alignment-design.md`。
- P3 · 比率太多會擠掉時間、分類欄位：比率一定進候選，還排在一般欄位前面，候選上限依舊是 8 個。焦點欄位加比率湊滿 8 個時，就沒有時間或分類欄位能配，分析只剩明細表。可以考慮至少留一個位置給時間或分類欄位。（B3 最終審查，2026-09-29）

- P2 · 送給 JEV 的題目和選項改成英文：jevai.wiki 的 state 頁寫「英文是主要訓練語言；其他語言（包括中日韓文字）雖可使用，但準確度較低」，現在的 instructions 和 criteria 全是中文（`AnalyzeService`、`HighlightService`）。使用者的需求和欄位名照原樣送。改完之後挑圖結果會變，要重新實測。（JEV 官方文件對照，2026-09-27）
- P3 · Score 的內插值不要拿來排序：jevai.wiki 的 primitives 頁說期望值只適合判斷門檻，`ConfigAssembler.candidates` 和組合排序卻直接比小數（2.75 排在 3.0 後面）。可以改成先比 `round(score)`，再比 confidence 或機率。（同上）

- P3 · 編輯、刪除合併來源的定義：合併定義建立之後不能改也不能刪，定義錯了只能另建一個，清單會越積越多。刪除要跟「刪除資料來源」一起設計（其他來源目前也不能刪），包括用到它的合併來源和儀表板要怎麼處理。（B1 spec 第 2、11 節）
- P2 · 承上，刪除資料來源要設計時一起處理：合併來源的 `sensitiveFrom` 現在找不到某個輸入來源就直接跳過（`DatasourceService.java` 的 `sensitiveFrom`，`if (input == null) continue`），沒有 fail closed。來源被刪掉之後，還在引用它的欄位應該變成敏感，不能悄悄當作不敏感。（B1 final review）

## 正式上線前

- P1 · 用真正的登入驗證取代 `X-User-Id` header：現在誰都能改這個 header 看到、動到別人的儀表板，連空字串都會被當成合法使用者 `""`。（backend ledger，pre-flight／Task 5）
- P2 · `docker/postgres/Dockerfile` 還沒建立（這個開發環境沒裝 Docker），部署前要補。（spec §7）
- P2 · `DS_` 環境變數目前沒有綁定特定 host，同一組密鑰理論上能被任何允許清單內的外部 API 拿去用，正式串接前要重新檢視這個設計。（backend ledger，Task 6）
- P3 · 監控和告警：JEV 逾時、呼叫失敗、快取命中率這些現在只寫進 log，沒有任何監控或告警機制。
- P2 · `jev.datasource.allowed-hosts` 在 `application.yml` 預設是空的，`backend/.env` 也沒有設定，所以外部 API 來源一律抓不到。「E2E採購儀表板」打開時 `/data` 回 400「未開放註冊外部 API」。要用外部 API 來源，就得把 ERP 的 host 加進允許清單。（合併後檢查，2026-09-28）

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
- ~~P3 · `DatasourcePanel` 用同一個 `busy` 旗標擋上傳／註冊和逐欄位的敏感勾選，操作其中一個時其他都會被鎖住。（frontend ledger，Task 4）~~ 已修正：新增來源移到對話框，改用自己的 `adding` 狀態，上傳或註冊時不會再鎖住欄位表。（UI 改版）
- ~~P3 · 分析預覽存檔失敗的錯誤訊息，取消對話框後不會清掉，下次打開還會看到舊的錯誤。（frontend ledger，Task 4）~~ 已修正：關掉預覽時會清掉錯誤訊息（依需求分析與調整）。
- P3 · 切換儀表板（`App.vue` 的 `open()`）沒有防過期回應的機制，快速切換時舊的回應可能蓋掉新畫面。（frontend ledger，Task 4）
- ~~P3 · PUT 更新儀表板時，body 裡的 `datasourceId` 會被完全忽略，換成別的資料源也不會報錯。（backend ledger，Task 10）~~ 已修正：來源改成記在每張圖上，PUT 會檢查每個來源都是自己的（多來源儀表板，`docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md`）。
- P3 · 外部 API 回應是空 body（204 或空陣列）時目前回 502，訊息不夠精準。（backend ledger，Task 6）
- P3 · 外部 API 回應如果不是 UTF-8（例如 Big5/MS950），現在的串流 JSON 解析不會另外處理編碼。（backend ledger，Task 6）
- P3 · 舊版 `.xls`（非 `.xlsx`）還是用一次載入整本活頁簿的 POI API，大檔案一樣有吃滿記憶體的風險；這次只有 `.xlsx` 改成邊讀邊處理。（backend ledger，Task 5）
- P3 · `RestClientException` 全部歸類成「無法連線」，其實也包含非連線類的失敗（例如回應格式解析錯誤），訊息可能誤導。（backend ledger，Task 7）
- ~~P3 · 第一次分析沒填需求時保留下來的圖，之後即使做了調整也不會被標「符合需求」，因為它一開始就不是照焦點欄位選出來的。（spec gap）~~ 已修正：調整時，保留下來的圖只要用到焦點欄位，就會加上 `options.focus`，標「符合需求」。
- ~~P3 · 調整還在等 JEV 回應時，「存為我的儀表板」沒有跟著停用，這時候按下去存的是調整前的版面。（frontend ledger）~~ 已修正：調整還在等 JEV 回應時，「存為我的儀表板」會停用。
- ~~P3 · 按「復原」把卡片放回去之後，焦點沒有回到那張卡片或任何有意義的地方，會落在 `document.body` 上。（frontend ledger）~~ 已修正：按「復原」之後，焦點會移到放回來那張卡片的「✕」。
- P3 · 用「各區每月營收」跑 E2E 第 1 步時，第一張卡片是「amount 依 region」，region × month 的組合排在第二張；排序沒有優先挑最貼近那句需求的組合。（E2E，Task 9）
- P3 · 輸入「加每月的數量趨勢」，JEV 給的是 amount 的折線圖，不是 qty；這是 JEV 判斷上的問題。（E2E，Task 9）

- P3 · 巢狀合併時，同一個輸入來源在結構裡出現兩次，就會被載入兩次（`DatasourceService.merge` 沒有在同一次載入裡去重），外部 API 輸入也會被打兩次。等巢狀合併常見、載入變慢時，再在同一次 `load` 裡加快取。（B1 spec 5.3）
- P2 · 一張卡用某個 API 來源、另一張卡用到同一個 API 來源的合併來源，兩張卡都會讓這個 API 來源被重複抓一次（`/data` 抓兩次、`highlight` 又各再抓一次）；同一個合併來源裡有多個外部 API 輸入時，也是一個一個依序抓，不是平行抓，每個最多可以等到 15 秒。（B1 final review）
- P3 · PUT 儀表板時，`basis` 裡 `intents` 是 null 或空陣列的分組也會通過驗證、被存下來（`DashboardService.requireValidBasis`）。前端顯示時會過濾掉，看不出來，只是資料不乾淨。（A final review）
- P3 · 手動組一個預覽請求，把自己的來源和別人的來源混在一起送 `/preview/data`，別人來源的卡片會回 `SOURCE_FAILED`，名稱是 null，不會洩漏資料；只有全部來源都失敗時才回 404。行為跟 spec 寫的「載入時 404」不完全一樣。（A final review）
- P3 · 已存儀表板移除卡片後，「不重抓資料」靠的是只生效一次的旗標 `ownUpdatePending`（`DashboardView.vue`）。如果 App 在同一個 Vue 更新週期裡連續換兩次設定（移除卡片後 PUT 回傳的設定，以及重新打開同一份儀表板），可能把重新打開誤判成移除而沒有重抓。現在的畫面流程不會觸發這種情況。（A final fix re-review）
- ~~P3 · 合併表單的「來源」選單寫著 2～5 個，但畫面沒有擋第 6 個，要等按預覽時才由後端回 400。（B1 Task 7 review）~~ 已修正：選滿 5 個之後，其他來源停用（B2）。
- P3 · 對照來源的數值欄位可能看不出來自哪個來源：兩個輸入來源都把 `amount` 的顯示名稱設成「金額」時，`銷售·amount` 繼承「金額」，`退貨·amount` 因為名稱重複不繼承，圖例變成「金額」和「退貨·amount」。可以讓拆開的數字欄位不繼承顯示名稱，或自動加上「來源名·」。（B2 final review）
- P3 · 製圖頁的欄位標籤會列出對照來源的「來源」欄。勾了它也不會有任何圖用到，因為分析時這欄一律當成 ignore，畫面上又沒有說明。可以對對照來源隱藏這個標籤。（B2 final review）
- P3 · 調整模式保留下來的圖，不會再檢查畫不畫得出東西，`ConfigAssembler.drawable` 只擋新產生的組合。這種圖只會出現在 B2 修正前的預覽裡，main 上不會有。（B2 final fix re-review）
- P3 · 對照來源上，雷達圖只要有一個數值的來源沒有那個分類欄位，整張就不出。也可以只拿掉那個數值，留下其他數值繼續畫。（B2 final fix re-review）

## 資料品質

- P2 · 民國年日期（例如 `113/09/24`）和不帶分隔符號的 8 碼日期（例如 `20240924`）目前都不會被判斷成日期欄位：前者年份不是 4 碼，正則比對不到；後者會先被數字規則吃掉，變成 `NUMBER`。（`SchemaInference.java` 的 `DATE`／`NUMBER` pattern 與型別判斷順序）
- P3 · `distinctCount` 和樣本值都是拿原始字串去算，不是先照欄位型別正規化再算；同一個值的不同寫法（日期 `2024-1-1` 跟 `2024/01/01`，或代碼欄位補零與否）可能被當成兩個不同值。（backend ledger，Task 3）
- P3 · 非敏感但幾乎每筆都不同的欄位（例如備註），如果被選成 series 或 y，等於間接把接近逐筆的明細送給 JEV，目前沒有這類欄位的偵測或筆數上限，屬於設計層級的問題。（backend ledger，Task 11）
- P3 · 合併來源裡整欄都是空值的欄位，會被當成「欄位不見了」：合併時空值不放 key（spec 5.1），而 `DashboardDataService` 是從資料列的 key 判斷欄位在不在。上傳檔案的全空欄位會保留 key，不會有這個問題。（B1 final review）
- P3 · 合併來源數字欄位的樣本值會顯示成 `1200.0`，不是 `1200`：schema 是拿已經轉成數字的資料列推斷的，`SchemaInference.infer` 用 `String.valueOf` 轉字串。欄位表和送給 JEV 的樣本都是這個格式。（B1 final review）

## 測試補強

- P3 · vitest 5 在 Node 23.9.0 上會印 EBADENGINE 警告，`package.json` 沒鎖 `engines`。（frontend ledger，Task 1）
- P3 · 幾支既有元件測試覆蓋不完整：`DashboardView` 的防過期 guard 只靠程式碼檢查過、沒有測試；`highlight` 只驗了第一個 guard，沒驗 error／highlight 兩個 guard 和「圖表先於標註顯示」以外的情境；勾選欄位的迴圈測試沒驗證數量；缺「已存檔模式可拖拉」這類正面對照組。（frontend ledger，Task 3、Task 4b）
- P3 · `api.test.js` 的 `rejects.toThrow` 是子字串比對、假設 `VITE_USER_ID` 沒設，`stubGlobal` 也沒有還原；測試檔沒有統一用 `enableAutoUnmount`，靠手動清空 `document.body` 頂著。（frontend ledger，Task 4b）
- P3 · 後端 round 2 沒有候選組合、候選欄位用滿全部欄位這兩種分支還沒有測試覆蓋；沒有測試驗證欄位改成敏感之後，已經快取的異常檢查結果會跟著失效。（backend ledger，Task 9、Task 11）
- P3 · 測試輸出裡還留著 Mockito self-attach 的 JDK 警告，和一段 JEV 500 情境印出的 WARN 加 stack trace，不影響結果但會洗版。（backend ledger，Task 4、Task 5、Task 9）
- P3 · `jev.dashboard` package 下有一個測試用的 controller，會被掃進所有 `@SpringBootTest`。（backend ledger，Task 1）
- P3 · 有兩份存過需求的舊儀表板屬於另一個使用者 ID，合併後檢查只在資料庫層確認它們的需求紀錄轉成了 `basis`，沒有在畫面上看過「依據」那一行。下次用那個使用者登入時順手確認。（合併後檢查，2026-09-28）
- ~~P3 · README 的「合併來源」一節沒寫查表的對接欄位最多 3 組（畫面上已經限制 3 組）。（B1 Task 10 review）~~ 已補上。
