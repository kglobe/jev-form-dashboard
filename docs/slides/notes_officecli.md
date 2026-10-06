# OfficeCLI 版工具筆記

- 工具：officecli 1.0.153
- 腳本：`build_officecli.py`，605 行（含註解和空行，空行 59 行）。Python 只負責組 batch JSON、讀 `content.py`，簡報內容都由 officecli 的 `add` / `set` / `batch` / `raw-set` 寫入
- 跑一次約 167 秒。其中第 12 頁要用 `view screenshot` 截 3 張 zoom 縮圖，就佔了約 75 秒（走 PowerPoint 原生算繪，每張 20～25 秒）；前 11 頁合計約 77 秒。早期還沒有縮圖和收尾步驟的版本跑 67～82 秒
- 驗收：`validate` 通過、`view issues` 0 筆、`view text` 沒有殘留字、12 頁都有備忘、`picture:no-alt` 0 筆；PowerPoint COM 能開，匯出 12 張 PNG 時沒跳修復提示（`qa-officecli\`）

## 功能逐項

標記說明：**原生** = officecli 屬性直接做到；**raw-set** = 要用 raw-set 補 XML；**繞路** = 用 officecli 的其他功能拼出來；**做不到** = officecli 沒辦法。

| 功能 | 做法 | 備註 |
|---|---|---|
| 漸層背景 | 原生 | `background=1F1C5C-5B1F6E-45`，寫出 `a:lin ang=2700000` |
| 玻璃卡 | 原生 | `preset=roundRect adj="adj:val N"`、`fill + opacity`、`line + lineOpacity`、`shadow=色-模糊-角度-距離-不透明度` |
| 亮卡（光暈＋陰影） | 原生 | `glow=00F2FE-8-45` 可以和 `shadow` 並存，兩個效果會一起寫進 effectLst |
| 文字陰影 | 原生 | 無填色文字框設 `shadow`，會自動寫到 run 的 rPr |
| 數字光暈（第 9 頁 12） | 原生 | 文字框設 `glow`，同樣寫到 rPr |
| orb 柔光團 | 原生 | ellipse + `opacity` + `softEdge`（pt） |
| morph | 原生 | `transition=morph-1200`。設了 morph 的頁，前後頁所有 shape 名稱都會自動加上 `!!` 前綴，所以內容 shape 的 id 和名稱要每頁不同，只讓 orb 配對 |
| fade / wipe / split / zoom / push 轉場 | 原生 | `fade`、`wipe-right`（`dir="r"`，從左往右擦）、`split-vertical-out`、`zoom`、`push-up` |
| 進場 fade、fly、appear、dissolve、blinds | 原生 | presetID 正確（10 / 2 / 1 / 9 / 3） |
| 進場 wipe | 原生＋raw-set | presetID 寫成 20（Wedge），要改成 22；而且不論方向，filter 都寫 `wipe(left)`，要依 subtype 改成 left/right/up/down |
| 進場 zoom | 原生＋raw-set | presetID 寫成 21（Wheel），改成 53 / subtype 16，PowerPoint 才認成 Zoom |
| 進場 split | 原生＋raw-set | presetID 寫成 15（Spiral In），改成 16 / subtype 26 |
| 進場 float in | raw-set 手寫 XML | officecli 的 `float` 其實是 presetID 12（Peek），從投影片外面飛進來。要改成 42、把 ppt_y 起點改成 `#ppt_y+.1`，再補一段手寫的 fade animEffect |
| 強調 pulse、teeter、transparency | 原生 | transparency 預設就是 50% |
| 強調 grow/shrink | 原生＋raw-set | 放大倍率固定 150%，用 raw-set 改成 115%；`autoReverse=true` 是原生屬性 |
| 退場 fade | 原生 | `class=exit` |
| 自訂路徑動畫 | 原生＋raw-set | `class=motion path=custom d="M 0 0 L 0 0.0356 E"` 可以直接用；presetID 寫成 26，改成 0，PowerPoint 才認成自訂路徑 |
| 連接線、圖片、表格、zoom 的動畫 | 繞路＋raw-set | `add animation` 只接受 shape 和 chart。做法是先放一個暫時的 dummy shape，把動畫掛在它身上，再用 raw-set 把 `spTgt/@spid` 改指到真正的目標，最後刪掉 dummy 和它的 bldP。第 7 頁刪完後 bldLst 會變空、不合 schema，要整個拿掉 |
| 圖表逐類別出現 | 原生＋raw-set | `chartBuild=category` 是原生的，但每個類別都會變成一次點擊；第 2 類要用 raw-set 改成接在第 1 類後面播 |
| 圖表樣式 | 原生 | 數列 alpha 用 8 碼色 `B3FFFFFF`、格線 `26FFFFFF:0.75`（格式是 **AARRGGBB**）；圖例、座標軸、資料標籤字型都是原生屬性；圖表區、繪圖區設 `none` |
| 表格樣式 | 原生 | `style=none`、`border.all=none`，儲存格逐格設 `fill + opacity`；水平格線用 `border.horizontal="0.75pt solid 40FFFFFF"` |
| 連接線（綁定形狀） | 原生 | `from`/`to` 用 `@id`，搭配 `fromSide`/`toSide` 和 `fromIdx`/`toIdx`。垂直的「是」線要指定 x/y/width/height，才不會畫成斜線 |
| 跳頁超連結 | 原生＋raw-set | `link=slide[N]` 只能指向已經存在的頁，要等全部頁建完再補。officecli 會把 hlinkClick 同時寫在 cNvPr 和每個 run，run 上那份會讓字變藍、加底線，要用 raw-set 刪掉，只留形狀層級的 |
| SVG icon | 原生 | `add picture src=*.svg` 會寫入 svgBlip。但 `currentColor` 不會被解析，要先產生一份換好顏色的暫存 SVG；PNG 後備圖只是一張 67 bytes 的空白圖，舊版檢視器看不到圖 |
| 講者備忘 | 原生 | `add --type notes` |
| Slide zoom | 原生＋繞路＋raw-set | `--type zoom` 是原生的，但縮圖是空白。補法是 `view screenshot` 截目標頁，`add picture` 取得圖片關聯，再用 raw-set 把 zoom 的 `a:blip r:embed` 指過去，最後用 raw-set 刪掉那張輔助圖片。全部在 officecli 內完成，不需要額外後處理 |
| 中文斷行 | 原生 | run 預設 `lang=en-US`，PowerPoint 不會套中文避頭尾規則，「、」「（」會落在行首或行尾。所有文字改設 `lang=zh-TW` 後正常。表格儲存格沒有 lang 屬性 |

## 偏離 brief 的地方

- 第 1 頁 Display 標題：brief 寫「行距約 1.1」。如果用 1.1 倍行距（spcPct），Noto Sans TC 的兩行字會撐出 1.75in 的框、壓到副標，所以改成固定 59.4pt（1.1 × 54pt），框和座標都照 brief
- 第 5 頁編號圓：0.36in 的圓裡，橢圓的文字區只有 71%，14pt 會溢出，改用 Caption 10.5 Bold
- 第 4 頁節點、第 6 頁六角形：內距改成 0，「組裝 mapping 與版面」才放得進一行，六角形的字也不會擠成三行
- 文字框高度：`view issues` 會依字型實際行高，把 brief 裡偏緊的框（H2 0.4in、頁尾 0.25in、KPI 數字 1.0in 等 49 個）報成溢出。收尾的 `fit_text_boxes()` 會照 officecli 的 `suggest.height` 把無填色文字框加高：靠上對齊的只改高度，置中的上下對稱撐開。字在畫面上的位置不變，x、y、寬也照 brief
- 第 2 頁第 3 張卡的 teeter：只搖卡片的話，卡片會和裡面的字錯開，所以卡和三段字一起搖（多 3 個 withPrevious 效果，共 4 個）

## 動畫驗收（`pptx_export.ps1 -Anim`）

EffectType 對照：10 淡入、2 飛入、22 擦去、48 縮放（Zoom）、16 分割、39 浮入（Float In）、1 出現、9 溶解、3 百葉窗、0 自訂路徑、75 脈衝、80 蹺蹺板、59 放大縮小、62 透明。Trigger：1 按一下、2 同時、3 接續。

| 頁 | 轉場代碼 | 效果數 | 內容 |
|---|---|---|---|
| 1 | 3849 fade | 4 | 卡 fade → 標題 zoom → 副標 wipe → meta fade，全部接續 |
| 2 | 3954 morph | 16 | 3 次點擊各 4 個飛入（卡＋3 段字），接著第 3 張卡整組 teeter |
| 3 | 3954 | 22 | 點擊左欄 10 個 wipe → 點擊右欄 10 個 wipe → 點擊 zoom＋接續 pulse |
| 4 | 2819 wipe | 18 | 節點 1 fade，7 組（線＋節點）fade 300ms 接續；點擊 3 個 JEV 節點同時 pulse |
| 5 | 3954 | 15 | 問卡 3 個 fade 接續；點擊：點 appear＋同時自訂路徑 → 答卡 split ×3 → 點 fade 退場；3 次點擊各 2 個 float in |
| 6 | 3954 | 15 | 7 次點擊，順序照 brief，線 wipe、節點 fade、標籤 appear、ignore dissolve |
| 7 | 3587 split | 1 | 表格 blinds，接續 |
| 8 | 3954 | 8 | 點擊：圖表第 1 類 wipe → 第 2 類接續；3 條解讀 fade 接續 |
| 9 | 3074 zoom | 12 | 卡 zoom → 箭頭 wipe → 卡 zoom → 箭頭 wipe → 卡 zoom，全部接續；點擊數字 12 放大縮小 |
| 10 | 3954 | 44 | 前 5 格 20 個 fade 同時（每格延遲 +150ms）；點擊第 6 格 zoom ×4，同時前 5 格 20 個 transparency |
| 11 | 3855 push | 16 | 4 次點擊，每次一列 4 個從左飛入 |
| 12 | 3954 | 9 | 3 次點擊，每次 badge＋文字＋zoom 一起 float in |

## 踩到的問題與 bug

1. presetID 寫錯：wipe→20、zoom→21、split→15、float→12、自訂路徑→26。實際播放的行為大致對（filter、animScale），但 PowerPoint 動畫窗格顯示的名稱是錯的；wipe 的方向也完全沒寫進 filter
2. 8 碼色的解析是 AARRGGBB，但 glow 的讀回值是 `#RRGGBBAA`，兩邊格式不一致。寫 `FFFFFF40` 會變成不透明的 `FFFF40`（黃色）
3. `add animation` 不支援 connector、picture、table、graphicFrame（zoom）
4. `link=` 會同時寫在 cNvPr 和所有 run；而且不能指向還沒建立的頁（batch 外不會緩衝）
5. morph 會把相鄰頁的 shape 名稱全部加上 `!!`，之後 connector 的 `from=@name=…` 會找不到 shape（"Frame @name=A not found"），要改用 `@id`
6. `raw-set` 只能改 slide、theme、master、layout、notes，不能改 chart part；XPath 沒命中時會直接出錯（不是 no-op），所以只能在確定有命中時才送
7. `view screenshot --page 5,7,10` 只會輸出第一頁；`add-part` 在 PPT 只能建 chart
8. 文字預設 `lang=en-US`，中文斷行會出問題（見上表）
9. 沒有 bodyPr 左右、上下分開設定內距的 add 參數；`margin` 只有一個值（set 時可以用 l,t,r,b）

## 沒能實際看到、只能靠推論的部分

- wipe 動畫 filter 方向的對照（subtype 8→left、2→right、1→up、4→down）和 wipe 轉場 `dir="r"` = 從左，是照 OOXML 慣例推的。-Anim 只看得到效果類型，看不到播放方向。2026-10-06 補驗：濾鏡對照和 PowerPoint 存出的 XML 一致
- PowerPoint 會依 nodeType 解讀 officecli 攤平的時間樹（with/after 都放在 mainSeq 頂層），-Anim 讀出的 trigger 全部正確，但實際放映的節奏沒有逐頁看過
