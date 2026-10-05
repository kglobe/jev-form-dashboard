# python-pptx 版工具筆記

- 腳本：`build_pptx.py`，664 行（含 70 行左右的 `_effect` 效果表、55 行的動畫序列組裝、10 行自我檢查）
- 成品：`JEV儀表板_python-pptx.pptx`；驗收截圖：`qa-python-pptx/`
- 執行時間：一般 0.9 秒。第一次跑要把 6 個 SVG 轉 PNG，33.5 秒（Chrome headless 並行），轉好的 PNG 會留在 `assets/` 沿用
- 版本：python-pptx 1.0.2，搭配 ppt-master 的 `pptx_fx.py`（沒改）

## 功能逐項

| 功能 | 做法 | 手寫 XML | 踩到的問題、備註 |
|---|---|---|---|
| 玻璃卡（填色透明、外框透明、陰影、圓角） | pptx_fx（fill_alpha、shadow、corner）＋手寫 | 2 行 | python-pptx 的線沒有透明度 API，外框 alpha 要自己補。自動形狀預設帶 `p:style`，會吃到主題的效果和線色，每個形狀都要拔掉 |
| 亮卡光暈 | pptx_fx.glow | 0 | 光暈畫在半透明形狀後面，會從 20% 的填色透出來，亮卡實際看起來比 ACC 20% 亮很多。MUTE 字放在亮卡上（第 2、9、10 頁）對比偏低。這是 PowerPoint 的算圖方式，換工具應該一樣 |
| 程式碼卡 | 同玻璃卡 | 0 | |
| 文字陰影、文字光暈（H1、Display、第 9 頁的 12） | 手寫 | 5 行 | python-pptx 沒有 run 層級的效果 API。`effectLst` 要放在 `solidFill` 後面、`latin` 前面，這是 schema 規定的順序 |
| 漸層背景 | 原生 `slide.background.fill.gradient()`（借 pptx_fx.gradient） | 0 | `gradient_angle=315` 就是 OOXML 的 45° |
| 柔光團 orb | 原生橢圓＋pptx_fx（soft_edge、fill_alpha），`shape.name` 原生可設 | 0 | 名字固定 `!!orb-*`，morph 靠這個配對 |
| morph | 手寫 | 6 行 | 要包 `mc:AlternateContent`（`p159:morph`），fallback 用 fade，`p14:dur="1200"`。pptx 的 `qn()` 不認 `mc` 前綴，搜尋要寫完整命名空間 |
| 其他轉場：fade、wipe、split、zoom、push | 手寫 | 每種 1 行，加插入位置共約 8 行 | python-pptx 沒有轉場 API。`dir` 是移動方向：從左擦 = `dir="r"`，往上推 = `dir="u"`。`<p:zoom/>` 被 VBA 回報成 ppEffectBoxOut（3073），PIA 另有 ppEffectZoomIn 等代碼，但 OOXML 沒有其他 zoom 元素可寫 |
| 動畫序列（點擊群組、afterPrevious 分段、withPrevious、延遲、bldLst） | 手寫 | 約 85 行共用 | pptx_fx 的 reveal／emphasize 只有 fade、pulse，時序固定，沒有延遲也不能混效果，所以另寫一套。有文字的形狀每多一個效果就要換新的 grpId，不然 PowerPoint 會丟掉後面的效果 |
| 進場：appear、fade、dissolve、blinds、split、wipe、zoom、fly、float | 手寫 | 每種 2～4 行 | zoom 用功能區上的「縮放」（presetID 53，VBA 名 FadedZoom，代碼 48），不是對話方塊裡的基本縮放（23）。float 是 presetID 42（VBA 名 Ascend，代碼 39）。wipe 的方向要寫兩次：presetSubtype 給 UI 看（從左 = 8），濾鏡 `wipe(right)` 才是實際播放方向 |
| 強調：pulse、teeter、grow/shrink、transparency | 手寫 | 2～7 行 | pulse（presetID 26）VBA 回報 75 FlashBulb，PowerPoint 2010 起介面改叫「脈衝」。teeter 照規格只套在第 3 張卡，卡內文字不跟著晃（只晃 2°，看不太出來） |
| 退場：fade out | 手寫 | 2 行 | 最後補一個 `style.visibility=hidden` |
| 路徑動畫 | 手寫 | 8 行 | 路徑座標是投影片寬高的比例。規格的距離只有 0.2 in（問卡底 3.4 到答卡頂 3.6），播起來很短 |
| 圖表逐類別 | 原生 add_chart＋手寫 | 動畫 5 行，樣式 7 行 | 動畫目標要寫 `graphicEl`（背景 gridLegend -3/-3，類別 category -4/i），bldLst 要 `bldGraphic`＋`bldChart bld="category"`。圖表區和繪圖區透明、數列透明度、格線透明度都沒有 API，各補 1～3 行 |
| 表格樣式 | 原生 add_table＋手寫 | 5 行 | 預設樣式要換成 No Style, No Grid。儲存格透明度、只留水平線要手寫 `lnL/lnR/lnT/lnB`，而且要插在填色前面。最後一列右側內距縮到 0.1 in，「）」才不會掉到下一行 |
| 連接線 | 原生 add_connector＋begin_connect（pptx_fx.connect）＋手寫 | 4 行 | python-pptx 的 begin_connect 只照外框算 4 個點，六角形的連接點編號不一樣（0 右中、3 左中），第 6 頁要自己算座標、自己寫 `stCxn/endCxn`。六角形底邊中點沒有連接點，兩條「是」沒綁 |
| 超連結跳頁 | 原生 `click_action.target_slide` | 0 | 目標頁要先建好，所以全部頁面建完才設 |
| icon | 原生 add_picture（PNG）；SVG 轉 PNG 用 Chrome headless 截 data URL | 0 | python-pptx 不收 SVG。Chrome 轉一張要 9 秒左右，改成並行加快取。要放 SVG 原檔可以手寫 `asvg:svgBlip`（PNG 當 fallback），這次沒做 |
| 講者備忘 | 原生 notes_slide | 0 | |
| 中文斷行 | 手寫 | 2 行 | run 沒設 `lang="zh-TW"` 的話，PowerPoint 不套避頭尾規則，第 9 頁的「，」會落在行首。只設 `hangingPunct` 沒用 |
| zoom 替代方案 | 原生（玻璃卡＋跳頁連結） | 0 | 做不出 slide zoom：XML 可以手寫，但 zoom 要放目標頁縮圖，python-pptx 沒辦法把投影片算成圖。照規格改成玻璃卡，字分兩段「第 N 頁」和短名，拿掉「｜」，因為 1.8 in 寬放不下一行 Body 字 |

## 做不到或沒驗到的

- slide zoom：見上表，用玻璃卡加連結代替
- 霧面玻璃的背景模糊：OOXML 沒有 backdrop blur，玻璃感只靠半透明填色和外框
- 播放方向：wipe、fly、push、split 的方向是照 OOXML 規格和 LibreOffice 的對照表寫的。-Anim 只看得到效果類型和觸發方式，PNG 也看不到動畫，方向要實際放映才能確認。原本想掃本機 PowerPoint 存出的 pptx 對照，被權限擋下

## 規格沒寫清楚、我自己定的

- 卡片的動畫連卡內文字一起播（withPrevious）：第 1 頁 kicker 跟著玻璃卡，第 5 頁「問／答」標籤和程式碼跟著卡，第 9 頁大數字和說明跟著卡
- 「行距約 1.1／1.3」換算成固定 pt（字級 × 倍數）
- 第 4 頁節點 1 用預設 400 ms，後面每一步 300 ms；teeter、grow/shrink 等沒寫時間的都用 400 ms
- 答卡的 split 方向沒寫，用垂直向外（跟第 7 頁轉場一致）
- 第 6 頁底部一句用 fade
- 第 4 頁「組裝 mapping 與版面」在最後一個空白手動換行，不然「面」會單獨一行
- 規格寬度配 14pt，第 5 頁解讀 1 的「錯」、第 9 頁的「位」、第 10 頁的「值」會單獨落行，沒改

## 驗收（PowerPoint 匯出，-Anim）

效果代碼：1 appear、2 fly、3 blinds、9 dissolve、10 fade（含退場）、16 split、22 wipe、39 float、48 zoom、59 grow/shrink、62 transparency、75 pulse、80 teeter、0 自訂路徑。觸發：1 點擊、2 同時、3 接續。

| 頁 | 轉場 | 效果數 | 和規格比對 |
|---|---|---|---|
| 1 | 3849 fade | 5 | PASS：卡片＋kicker fade 500、標題 zoom 600、副標 wipe、meta fade，全部自動 |
| 2 | 3954 morph | 13 | PASS：三次點擊 fly 從下（卡內文字 with），第 3 張接 teeter |
| 3 | morph | 22 | PASS：左欄 wipe 從左、右欄 wipe 從右、底句 zoom 再接 pulse |
| 4 | 2819 wipe 從左 | 18 | PASS：節點 1 自動 fade，7 步「線＋節點」接續；點擊 3 個 JEV 節點同時 pulse；4 個節點有跳頁連結（5、7、9、11） |
| 5 | morph | 15 | PASS：問卡自動 fade；點擊 appear＋路徑（同時）→ 答卡 split → 圓點退場；3 次點擊 float |
| 6 | morph | 15 | PASS：7 次點擊，線 wipe、標籤 appear、ignore dissolve |
| 7 | 3587 split 垂直向外 | 1 | PASS：表格自動 blinds |
| 8 | morph | 9 | PASS：點擊圖表背景 wipe，2 個類別接續，3 條解讀接續 fade |
| 9 | 3073 zoom | 12 | PASS：卡、箭頭交錯自動播；點擊 12 grow/shrink |
| 10 | morph | 44 | PASS：前 5 格同時起播、每格延遲 +150 ms；點擊第 6 格 zoom，同時 20 個形狀 transparency 50% |
| 11 | 3855 push 往上 | 16 | PASS：4 次點擊，每列 fly 從左 |
| 12 | morph | 9 | PASS：3 次點擊，行動和回看入口一起 float；3 張入口有跳頁連結（5、7、10） |

- 版面：12 頁逐頁看過，沒有重疊、溢出，箭頭方向正確，座標照規格
- PowerPoint 開檔（COM 唯讀開啟、匯出）沒有修復提示，python-pptx 也能重新開啟
- 修了 2 輪：第 1 輪修節點 6 斷行、表格最後一列「）」掉行；第 2 輪補 `lang="zh-TW"` 修「，」落行首
