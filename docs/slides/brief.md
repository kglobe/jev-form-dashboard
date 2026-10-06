# JEV 儀表板簡報：設計規格（python-pptx 版與 OfficeCLI 版共用）

兩份簡報要長得一樣：同一份文案（`content.py`）、同一套版面座標、同一套動畫劇本。差別只在用哪個工具做，這樣才比得出工具差異。

## 1. 目標與受眾

- 受眾：內部開發者／技術團隊，看得懂 JSON、mapping、gridPos
- 行動：理解「JEV 只判斷、合法性彙總版面歸後端規則」的分工，回去套到自己專案
- 框架：SCQA（問題 → 分工 → 流程細節 → 收斂與隱私 → 重用 → 行動）
- 關鍵頁：第 3 頁

## 2. 全域規格

### 投影片與格線（單位：吋）

- 尺寸 10 × 5.625（16:9）
- 邊距左右 0.5、上 0.4、下 0.35
- 標題帶 y 0.4–1.3（`(0.5, 0.4, 9.0, 0.9)`，垂直置中，最多 2 行）
- 內容區 y 1.4–4.95，寬 9.0
- 頁尾帶 y 5.0–5.25：左邊 `content.FOOTER_LABEL`（Caption、MUTE，`(0.5, 5.0, 5.0, 0.25)`），右邊頁碼 `05` 兩位數（Caption、MUTE、靠右，`(8.5, 5.0, 1.0, 0.25)`）。第 1 頁不放頁尾

### 風格：Glassmorphism 霧面玻璃

| Token | 值 | 用途 |
|---|---|---|
| BG1 → BG2 | `1F1C5C` → `5B1F6E` | 每頁背景線性漸層，左上深靛到右下深紫（OOXML `a:lin ang` = 45°；python-pptx `gradient_angle` = 315；officecli `background=1F1C5C-5B1F6E-45`） |
| INK | `FFFFFF` | 主要文字 |
| MUTE | `D6D3F0` | 次要文字、說明、頁尾 |
| LINE | `B8B4E0` | 連接線 |
| ACC | `00F2FE` | 唯一 accent（電光青），全份只用這一個 |
| ACC_INK | `1F1C5C` | accent 實心底上的文字 |
| ORB_V / ORB_P / ORB_C | `667EEA` / `F093FB` / `00F2FE` | 背景柔光團 |

- 字型：Noto Sans TC（latin 和 ea 都要設），程式碼用 Consolas（ea 仍設 Noto Sans TC）
- 字級角色（全份只用這 5 個值，程式碼另用 11pt）：Display 54 Bold／H1 26 Bold／H2 16 Bold／Body 14 Regular／Caption 10.5 Regular
- 標題（H1）和 Display 文字加微陰影：外陰影 blur 4pt、dist 1.5pt、dir 90°、黑色 alpha 35%（style 規格「白字加微陰影」）
- 不加標題底線（AI 套路）

### 元件

| 元件 | 規格 |
|---|---|
| 玻璃卡 `glass` | 圓角矩形，圓角半徑 0.12 in；填白 alpha 12%；外框白 alpha 35%、0.75pt；外陰影 blur 18pt、dist 6pt、dir 90°、黑 alpha 25% |
| 亮卡 `hot`（JEV 步驟、強調格） | 同玻璃卡，但填 ACC alpha 20%、外框 ACC 不透明 1.25pt，加光暈 ACC radius 8pt alpha 45% |
| 深色程式碼卡 `code` | 圓角半徑 0.12；填 `0F0D2E` alpha 55%；外框白 alpha 25% 0.75pt；無陰影 |
| 編號圓 `badge` | 圓形，外框 ACC 1.25pt，無填色，文字 ACC Bold，置中 |
| 連接線 | 直線或肘形，LINE 色 1.25pt，終點三角箭頭；能綁在形狀上就綁 |
| 柔光團 orb | 橢圓（正圓），無外框；填色 alpha：violet 55%、pink 45%、cyan 30%；邊緣羽化 softEdge = 直徑 × 72 × 0.25 pt。每頁最先加（在最底層），名稱固定 `!!orb-violet`、`!!orb-pink`、`!!orb-cyan`，morph 靠名字配對 |

### Orb 位置表（中心 cx, cy, 直徑 d，單位吋，可以出血）

| 頁 | violet | pink | cyan |
|---|---|---|---|
| 1 | 8.3, 1.0, 4.4 | 6.4, 5.2, 3.6 | 0.9, 5.1, 1.8 |
| 2 | 9.7, 4.9, 3.6 | 0.3, 0.3, 3.2 | 5.0, 5.7, 1.6 |
| 3 | 0.2, 5.3, 3.4 | 9.8, 0.2, 3.6 | 5.0, 0.0, 1.4 |
| 4 | 5.0, 6.0, 4.0 | -0.2, 2.8, 3.0 | 10.0, 2.8, 1.8 |
| 5 | 9.9, 0.6, 3.4 | 4.6, 6.0, 3.0 | 0.0, 0.4, 1.6 |
| 6 | 0.0, 0.2, 3.2 | 10.0, 5.4, 3.8 | 6.0, 5.9, 1.4 |
| 7 | 10.1, 3.0, 3.6 | 1.5, 6.0, 3.2 | 4.8, -0.3, 1.6 |
| 8 | 3.5, 6.1, 3.8 | 9.9, 0.0, 3.2 | 0.1, 2.6, 1.8 |
| 9 | 5.0, -0.6, 4.2 | 0.0, 5.6, 3.4 | 9.9, 5.5, 1.8 |
| 10 | -0.3, 3.5, 3.4 | 10.2, 1.8, 3.4 | 7.0, 6.0, 1.4 |
| 11 | 9.6, 5.8, 3.8 | 2.0, -0.4, 3.0 | 0.2, 4.8, 1.6 |
| 12 | 8.0, 1.2, 4.2 | 6.0, 5.4, 3.4 | 1.0, 5.2, 1.8 |

### 換頁轉場（使用者要求「全部能用的都用」，覆寫 ppt-master 不加轉場的規則）

| 頁 | 轉場 |
|---|---|
| 1 | fade |
| 2, 3, 5, 6, 8, 10, 12 | morph（orb 依名字平滑移動），約 1.2 秒 |
| 4 | wipe（從左） |
| 7 | split（垂直、向外） |
| 9 | zoom |
| 11 | push（往上） |

### 動畫用語

- 「自動」= 進頁後依序自動播（第一個 afterPrevious/withPrevious），「點擊」= 按一下播
- 時間沒寫就 400ms
- 每頁講者備忘一律寫入（`content.py` 各頁的 `notes`）

## 3. 逐頁規格

### 第 1 頁　封面（O1）`content.COVER`

- 玻璃卡 `(0.35, 1.2, 7.2, 3.55)`
- kicker：H2 16、ACC、字距 3pt，`(0.7, 1.45, 6.6, 0.35)`
- 標題：Display 54、兩段（`title` 兩行），`(0.7, 1.85, 6.6, 1.75)`，行距約 1.1
- 副標：H2 16、MUTE，`(0.7, 3.7, 6.6, 0.4)`
- meta：Caption、MUTE，`(0.7, 4.2, 6.6, 0.3)`
- 動畫（全部自動）：玻璃卡 fade 500 → 標題 zoom 進場 600 → 副標 wipe 從左 → meta fade

### 第 2 頁　三個問題（G1，全份唯一一次三等分卡）`content.PROBLEMS`

- 標題
- 三張玻璃卡：x = 0.5 / 3.6 / 6.7，y 1.6，w 2.8，h 2.9；第 3 張用亮卡
- 卡內：編號 `01`～`03` H2 ACC `(x+0.25, y+0.25, 1.0, 0.4)`；卡片標題 H2 INK `(x+0.25, y+0.8, 2.3, 0.45)`；說明 Body MUTE `(x+0.25, y+1.35, 2.3, 1.35)`，行距約 1.3
- 動畫：三張卡各自「點擊」fly in 從下方（卡和卡內文字同時，卡內文字 withPrevious）；第 3 張出現後接著 teeter 強調（afterPrevious）

### 第 3 頁　分工（C1，關鍵頁）`content.SPLIT`

- 標題（可能 2 行）
- 左玻璃卡 `(0.5, 1.55, 4.35, 2.75)`，欄標 H2 ACC `(0.8, 1.75, 3.8, 0.4)`；右玻璃卡 `(5.15, 1.55, 4.35, 2.75)`，欄標 H2 INK `(5.45, 1.75, 3.8, 0.4)`
- 每欄 4 條要點 Body INK，從 y 2.3 起每條間距 0.48，條前一顆小圓點（直徑 0.1，左欄 ACC、右欄 MUTE）
- 底部一句：Body Bold ACC `(0.5, 4.5, 9.0, 0.4)`
- 動畫：點擊 → 左卡＋左欄文字 wipe 從左；點擊 → 右卡＋右欄文字 wipe 從右；點擊 → 底部一句 zoom 進場，接著 pulse 強調（afterPrevious）

### 第 4 頁　全流程（P3，原生形狀＋連接線）`content.FLOW`

- 標題
- 8 個節點，w 1.9 h 0.8，蛇形兩列：第 1 列 y 1.65 依序 1→4，x = 0.5 / 2.867 / 5.233 / 7.6；第 2 列 y 3.25 由右往左 5→8，x = 7.6 / 5.233 / 2.867 / 0.5
- 節點：一般用玻璃卡，JEV 步驟用亮卡；兩段文字置中：步驟編號 `01` Caption ACC、標籤 13pt Bold INK
- 連接線：1→2→3→4 右接左；4→5 下接上（直線）；5→6→7→8 左接右；全部帶箭頭
- 節點超連結：`nodes` 第三欄有頁碼的節點，點了跳到那頁
- 圖說：Body MUTE `(0.5, 4.4, 9.0, 0.35)`
- 動畫：自動依序，節點 1 fade，然後每一步「連進來的線＋節點」fade 300ms；全部出完後，點擊 → 3 個 JEV 節點同時 pulse

### 第 5 頁　結構化題目（K3，程式碼代替圖表）`content.QUESTIONS`

- 標題
- 問卡（code）`(0.5, 1.55, 5.7, 1.85)`：標籤「問」Caption ACC `(0.7, 1.65, 0.5, 0.25)`；程式碼 Consolas 11 INK 7 行 `(0.7, 1.9, 5.3, 1.45)`
- 答卡（code）`(0.5, 3.6, 5.7, 1.25)`：標籤「答」`(0.7, 3.7, 0.5, 0.25)`；程式碼 3 行 `(0.7, 3.95, 5.3, 0.8)`
- 右側 3 條解讀，x 6.5 起：編號圓 badge 直徑 0.36 `(6.5, y)`，文字 Body INK `(7.0, y-0.05, 2.5, 0.95)`，y = 1.6 / 2.65 / 3.7
- 動畫：問卡自動 fade；點擊 → 一顆 ACC 小圓點（直徑 0.14，加光暈）在問卡底部中央出現（appear），沿自訂路徑往下移到答卡頂端（motion path，800ms，withPrevious），接著答卡 split 進場（afterPrevious），小圓點 fade 退場（afterPrevious）；之後每點擊一次出一條解讀（float in）

### 第 6 頁　防呆（P2，決策流程）`content.GUARD`

- 標題
- 起點 `start`：玻璃卡 `(0.5, 2.0, 1.6, 0.9)`
- 判斷 1 `check1`：六角形 hexagon 玻璃樣式 `(2.5, 1.95, 2.3, 1.0)`，文字 13pt
- 判斷 2 `check2`：六角形 `(5.2, 1.95, 2.5, 1.0)`，文字 13pt
- 採用 `accept`：玻璃卡 `(8.0, 2.0, 1.5, 0.9)`
- 改判 ignore `ignore`：亮卡 `(2.9, 3.55, 4.4, 0.75)`，H2
- 連接線：start→check1；check1→check2（標「否」）；check2→accept（標「否」）；check1→ignore（標「是」）；check2→ignore（標「是」）。標籤 Caption MUTE，放在線旁邊
- 底部一句：Body Bold ACC `(0.5, 4.55, 9.0, 0.35)`
- 動畫：點擊逐步：[start] → [線, check1] → [「是」線, 標籤, ignore] → [「否」線, 標籤, check2] → [「是」線, 標籤] → [「否」線, 標籤, accept] → [底部一句]。節點 fade、線 wipe（方向順著線）、標籤 appear、ignore 用 dissolve

### 第 7 頁　合法組合表（C2，表格物件）`content.LEGAL`

- 標題
- 原生表格 `(0.5, 1.55, 9.0, 3.1)`，2 欄寬 4.0 / 5.0，表頭＋5 列，列高約 0.5
- 表頭：填 ACC 實心，文字 ACC_INK H2；內文列：填白 alpha 8% / 14% 交替，文字 Body INK；格線只留水平線，白 alpha 25%
- 動畫：表格自動 blinds（水平）進場

### 第 8 頁　平均不是加總（K3，原生圖表）`content.AVERAGE`

- 標題
- 群組直條圖 `(0.5, 1.5, 5.8, 3.35)`：類別 `categories`，數列「加總」白色 alpha 約 70%、「平均」ACC；資料標籤白 12pt；座標軸文字 MUTE 11pt；格線白 alpha 15%；圖例在上方 MUTE 11pt；圖表區和繪圖區透明；不放圖表標題
- `tag`「示意數字」：Caption MUTE 靠右 `(4.8, 1.5, 1.5, 0.25)`
- 右側玻璃卡 `(6.6, 1.55, 2.9, 3.3)`，3 條解讀 Body INK，條前編號 Caption ACC
- 動畫：點擊 → 圖表 wipe 從下方、依類別逐一出現（chart build by category）；接著 3 條解讀依序 fade（afterPrevious）

### 第 9 頁　兩輪收斂（K1）`content.FUNNEL`

- 標題
- 3 張玻璃 KPI 卡 x = 0.5 / 3.7 / 6.9，y 1.6，w 2.6，h 2.5；第 3 張亮卡
- 卡內：數字 Display 置中 `(x, 1.85, 2.6, 1.0)`（第 3 張數字 ACC 加光暈，其餘 INK）；說明 Body MUTE 置中 `(x+0.2, 3.0, 2.2, 0.9)`
- 卡間箭頭：rightArrow 形狀 `(3.175, 2.65, 0.45, 0.35)` 和 `(6.375, 2.65, 0.45, 0.35)`，填白 alpha 50%
- 底部一句：Body Bold INK `(0.5, 4.4, 9.0, 0.4)`
- 動畫：自動依序：卡 1 zoom → 箭頭 1 wipe 從左 → 卡 2 zoom → 箭頭 2 wipe → 卡 3 zoom；點擊 → 數字「12」grow/shrink 強調（放大約 115% 再回來）

### 第 10 頁　隱私（G2，2×3 icon 格）`content.PRIVACY`

- 標題
- 6 格：x = 0.5 / 3.6 / 6.7，y = 1.55 / 3.1，w 2.8，h 1.35；前 5 格玻璃卡，第 6 格亮卡
- 格內：icon 0.45 見方 `(x+0.25, y+0.25)`；標題 H2 INK `(x+0.85, y+0.27, 1.8, 0.4)`；說明 Body MUTE `(x+0.25, y+0.78, 2.35, 0.5)`
- icon：`assets/<名稱>.svg`（Phosphor regular，fill 是 currentColor）。前 5 個白色，第 6 個 ACC。python-pptx 不能放 SVG，要轉 PNG（本機 Chrome headless 可轉，見第 4 節）；officecli 可以直接放 SVG
- 圖說：Body MUTE `(0.5, 4.6, 9.0, 0.3)`
- 動畫：前 5 格自動錯開 fade（withPrevious，每格延遲 +150ms）；點擊 → 第 6 格 zoom 進場，同時前 5 格 transparency 強調到 50%（withPrevious）

### 第 11 頁　重用（C2 變體，用形狀排成表）`content.REUSE`

- 標題
- 表頭（y 1.5, h 0.4）：Caption Bold，`場景` MUTE `(0.7, 1.5, 2.0, 0.4)`、`JEV 回答` ACC `(2.9, 1.5, 3.1, 0.4)`、`後端決定` MUTE `(6.2, 1.5, 3.2, 0.4)`
- 4 列玻璃卡 `(0.5, y, 9.0, 0.65)`，y = 1.95 / 2.7 / 3.45 / 4.2；列內：場景 H2 INK `(0.7, y, 2.0, 0.65)`、JEV 回答 Body INK `(2.9, y, 3.1, 0.65)`、後端決定 Body MUTE `(6.2, y, 3.2, 0.65)`，皆垂直置中
- 動畫：每點擊一次，一列（卡＋三段文字）fly in 從左

### 第 12 頁　結論與行動（E1）`content.ACTIONS`

- 標題
- 3 條行動，左側：badge 直徑 0.45 `(0.5, y+0.27)`，文字 Body INK `(1.15, y, 5.3, 1.0)` 垂直置中；y = 1.55 / 2.65 / 3.75
- 右側：每條行動對一個回看入口 `(7.5, y, 1.8, 1.0125)`（16:9），對應 `items` 的頁碼
  - officecli：用原生 slide zoom（`--type zoom`）。zoom 預設縮圖是空白，要想辦法補上目標頁的縮圖，優先用 officecli 自己的功能（例如 `view screenshot`、`add-part`、`raw-set`）
  - python-pptx：做不出 zoom，改成玻璃卡寫「第 N 頁｜短名」，加跳頁超連結
- 頁尾那一行改放 `footer`：Caption MUTE `(0.5, 4.95, 7.5, 0.25)`（取代 FOOTER_LABEL），右邊頁碼照放
- 動畫：每點擊一次，一條行動和它的回看入口一起 float in

## 4. 已知的工具細節（共用前置階段實測，可直接用）

### 通用

- 驗收用 PowerPoint 本體匯出：`pwsh -File C:\Users\I26436\.claude\skills\ppt-master\scripts\pptx_export.ps1 <pptx> -OutDir <資料夾> -Anim`。`-Anim` 列出每頁 transition 代碼與動畫（EffectType/Trigger）。另一個 agent 可能同時在用 PowerPoint，COM 出錯就等幾秒重試
- Python 用 `python`，不要用 `python3`
- SVG 轉 PNG（Chrome headless）實測可用：先把 svg 的 `fill="currentColor"` 換成 `width="256" height="256" fill="#FFFFFF"`，再跑 `"C:/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 --window-size=256,256 --screenshot=<Windows 絕對路徑.png> file:///<svg 路徑>`，得到透明底 256×256 PNG

### python-pptx（1.0.2）

- ppt-master 的效果 helper：`C:\Users\I26436\.claude\skills\ppt-master\scripts\pptx_fx.py`（deck、cjk_font、spacing、corner、gradient、fill_alpha、shadow、glow、soft_edge、connect、reveal(fade)、emphasize(pulse)）。範例：`C:\Users\I26436\.claude\skills\ppt-master\demo-dark-premium\build_deck.py`
- pptx_fx 只有 fade 和 pulse，刻意不做轉場。其他效果（zoom、wipe、fly、float、split、blinds、dissolve、appear、teeter、grow/shrink、transparency、exit、motion path、chart build、轉場、morph）要自己寫 OOXML，寫在 build 腳本裡，不要改 skill 檔

### OfficeCLI（1.0.153）

- skill 全文已存：`C:\Users\I26436\AppData\Local\Temp\claude\D--GitHub-jev-form-dashboard\3ee83258-9bf3-4c4c-8323-2962c3bb1cca\scratchpad\oc-pptx.md`（pptx 基礎）、`...\scratchpad\morph-ppt.md`（morph）。不確定屬性就跑 `officecli help pptx <element>`
- 已驗證可用：`officecli set f.pptx / --prop slideWidth=10in --prop slideHeight=5.625in`；`background=1F1C5C-5B1F6E-45`；shape 的 `fill=FFFFFF --prop opacity=0.15`、`line=FFFFFF:0.75 --prop lineOpacity=0.4`、`shadow=000000-18-90-6-25`（色-模糊-角度-距離-不透明度）、`glow=00F2FE-8-45`（色-半徑-不透明度）、`softEdge=4`（pt）；`font="Noto Sans TC"` 會一起設 ea；animation 用 `--type animation --prop effect=… --prop class=entrance|emphasis|exit|motion --prop trigger=onClick|withPrevious|afterPrevious --prop duration=…`；motion 用 `class=motion --prop path=custom --prop "d=M 0 0 L 0 0.3 E"`；圖表動畫 `--prop chartBuild=byCategory`；`transition=morph`、`transition=split-vertical-out` 等；`--type zoom --prop target=N`；`--type notes`
- 已發現的問題：`effect=zoom` 寫成 presetID 21（PowerPoint 認成 Wheel，實際播放仍是縮放），`effect=wipe` 寫成 presetID 20（PowerPoint 認成 Wedge，實際是 wipe 濾鏡）；mermaid `--type diagram --prop render=native` 不支援 `<br/>`、樣式是預設淺藍框，第 4 頁不適用；zoom 的縮圖是空白圖

## 5. 交付

- 兩份都放 `docs/slides/`：`build_pptx.py` → `JEV儀表板_python-pptx.pptx`；`build_officecli.py`（或 .sh）→ `JEV儀表板_officecli.pptx`
- 各自的驗收截圖放 `docs/slides/qa-python-pptx/`、`docs/slides/qa-officecli/`
- 各自寫一份工具筆記 `notes_python-pptx.md` / `notes_officecli.md`：用了哪些功能、哪些要手寫 XML 或繞路、做不到什麼、踩到的 bug、腳本行數、跑一次多久

## 6. 後續更新（2026-10-06，只有 python-pptx 版）

比較做完後，python-pptx 版改用 MP072 配色。OfficeCLI 版保留上面的原始配色，當作比較時的紀錄。

| Token | 新值 | 用途 |
|---|---|---|
| BG1 → BG2 | `1B2632` → `2C3B4D` | 背景深藍漸層，角度不變 |
| INK | `EEE9DF` | 主要文字，也當玻璃卡的填色 |
| MUTE、LINE | `C9C1B1` | 次要文字、連接線 |
| ACC | `FFB162` | 唯一 accent（橘） |
| ACC_INK | `1B2632` | accent 實心底上的文字 |
| CODE_BG | `111921` | 程式碼卡 |
| orb | `!!orb-orange` `FFB162` 35%、`!!orb-rust` `A35139` 55%、`!!orb-cream` `EEE9DF` 12% | 背景柔光團，位置表照舊 |

- 亮卡改成 BG2 85% 不透明的底，外框和光暈仍用 ACC。光暈畫在形狀後面，原本 20% 的填色會讓光暈整片透出來，上面的字對比不夠
- 動畫和轉場改用 ppt-master `pptx_fx.py` 的 `animate()`、`fx()`、`transition()`，`build_pptx.py` 從 664 行減到 506 行
- wipe 進場的濾鏡原本整組寫反（從左寫成 `wipe(right)`）。用 PowerPoint 自己存的 XML 對照後修正為從左 `wipe(left)`、從右 `wipe(right)`、從上 `wipe(up)`、從下 `wipe(down)`
