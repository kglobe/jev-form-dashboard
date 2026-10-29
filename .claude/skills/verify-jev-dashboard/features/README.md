# JEV 儀表板功能地圖

這個資料夾列出使用者看得到的功能，每個功能一個檔，寫怎麼走到、怎麼用 agent-browser 操作、看到什麼才算過。預期數字和完整步驟都在 `docs/testing/e2e-playbook.md`（劇本），這裡只寫步驟編號，不重抄。

## 基本前提

- 照 `../SKILL.md` 的 Launch 開好環境，Doctor 結束碼 0。
- 管理員是 `e2e-admin`／`E2eAdmin-2026`（後端啟動時用環境變數建的）。其他帳號照劇本 2.2，S02 才會建出來。
- 每個帳號一個 session：`--session vjd-$RUN-<帳號>`，第一個指令帶 `--init-script "$RUN_DIR/jev-init.js"`，而且不接 pipe。
- 固定資料直接用 `<checkout>/docs/testing/fixtures/` 的絕對路徑上傳。
- 不操作別人開的環境。8080、5180、9222 是使用者的。

## 操作慣例

- 劇本的章節會接著前一節留下的資料跑。只跑其中一個功能時，看那個檔的 `Preconditions:`，缺什麼就先照劇本補，或在 `results.md` 寫明哪些預期因此要調整。
- 優先用 snapshot 的 ref 和看得到的文字（`find role`、`find label`、`find text`），其次才用 `[data-chart-id="c1"]`、`.card-drag` 這類 CSS。
- 畫面上的中文照抄，不翻譯、不改寫。

## 證據和略過

- 每個標「截圖」的步驟都存檔，並用 Read 打開來看（劇本第 6 節）。
- 改了東西就從第二個地方讀回來：`jev.source`、`jev.dashboard()`、重新整理後的畫面。
- 走不到的入口，寫下試了什麼指令、缺了什麼前提。不要拿另一條路代替，然後記成已驗證。

## 功能檔的格式

開頭一個 H1 和一段使用者看得到的行為說明，接著四個 H2，順序固定：

1. `Sub-features`：短 ID 加一行說明。
2. `How to get to it (user POV)`：使用者從哪裡進來。
3. `Driving it with agent-browser`：先寫 `Preconditions:`，再用粗體標籤配上指令和看得到的結果。
4. `Gotchas`：會浪費時間或讓結果不算數的陷阱。

## 功能

- [登入和帳號](./login-accounts.md)：登入、登出、帳號管理、改密碼（S01～S03）。
- [上傳、分析、存成儀表板](./upload-analyze-save.md)：上傳 CSV／Excel、依需求分析、存檔（S04～S07）。2026-10-23 用 agent-browser 實跑過。
- [已存的儀表板](./saved-dashboard.md)：重開、拖拉、縮放、移除、異常標註（S08）。
- [儀表板篩選器](./filters.md)：期間、分類 chip、存成預設、檢視者（S19）。
- [KPI 大數字卡](./kpi-cards.md)：分析自動放、加 KPI 卡、門檻（S21）。
- [本期重點](./period-digest.md)：列哪幾張、寫法、按了捲到卡片、跟著篩選、拿掉卡片、檢視者和訪客（S25）。

還沒寫成功能檔的劇本章節：S09～S18、S20、S22～S24（劇本第 8 節有對照）。要驗這些就直接照劇本那一節跑，再照 pstack 的 `maintain-verification-skill` 補一個檔。
