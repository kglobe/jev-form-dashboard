# JEV 儀表板簡報的共用文案（已過 humanizer-zh）。
# build_pptx.py 和 build_officecli.py 都從這裡取字，兩份簡報的文字才會一模一樣。

COVER = {
    "kicker": "JEV 智慧化產製儀表板",
    "title": ["JEV 只做判斷，", "其他交給規則"],
    "subtitle": "智慧化產製儀表板的流程與方法",
    "meta": "內部技術分享 · 2026.10",
    "notes": "今天講的是一種分工方式，不限這個專案。希望大家聽完，回去能把自己專案裡叫模型做的事，拆成題目和規則兩半。",
}

PROBLEMS = {
    "title": "直接叫模型產設定檔，會碰到三個問題",
    "cards": [
        ("結果不可重現", "同一份資料問兩次，可能拿到兩份不同的設定檔"),
        ("畫出不合理的圖", "例如把兩個分類欄位硬湊成散佈圖"),
        ("題目塞不進一次請求", "50 個欄位兩兩配對就有 1225 組，很容易超過 64k token"),
    ],
    "notes": "這三個問題是一開始就想避開的。第三個最實際，ERP 資料常見 20 到 50 個欄位。",
}

SPLIT = {
    "title": "JEV 只回答題目，合法性、彙總和版面都由後端決定",
    "left": ("JEV 判斷", ["欄位重不重要", "欄位扮演什麼角色", "這組欄位該畫哪種圖", "資料有沒有異常"]),
    "right": ("後端規則決定", ["哪些圖表組合合法", "資料怎麼彙總", "mapping 和版面怎麼排", "答案要不要採用"]),
    "bottom": "同一份資料加同一組答案，永遠組出同一份設定檔",
    "notes": "這頁是今天的重點。模型完全不碰合法性、彙總和版面。就算它選了不合理的組合，只要規則表裡沒有，圖就畫不出來。",
}

FLOW = {
    "title": "八個步驟裡，JEV 只出現在三步",
    # (標籤, 是否 JEV 步驟, 點擊跳到第幾頁 or None)
    "nodes": [
        ("資料接入", False, None),
        ("型別推斷", False, None),
        ("第一輪：欄位評估", True, 5),
        ("候選與合法組合", False, 7),
        ("第二輪：選圖", True, 9),
        ("組裝 mapping 與版面", False, None),
        ("預覽與儲存", False, None),
        ("載入時異常檢查", True, 11),
    ],
    "caption": "亮色是 JEV 回答的步驟，其他步驟都由後端程式處理",
    "notes": "第三步、第五步和最後一步才問 JEV，其他步驟寫死在程式裡，可以寫單元測試。異常檢查是非同步的，失敗也不影響已經畫好的圖。",
}

QUESTIONS = {
    "title": "第一輪每個欄位問兩題，JEV 回的是分數和選項",
    "ask_label": "問",
    "ask_code": [
        '"imp_2": {',
        '  "type": "score",',
        '  "criteria": ["不重要，略過", "次要", "重要", "核心指標"] }',
        '"role_2": {',
        '  "type": "choice",',
        '  "criteria": { "dimension": …, "measure": …,',
        '                "time": …, "ignore": … } }',
    ],
    "answer_label": "答",
    "answer_code": [
        '"imp_2":  { "type": "score", "score": 3.0 }',
        '"role_2": { "type": "choice", "choice": "measure",',
        '            "confidence": 0.97 }',
    ],
    "points": [
        "key 用索引 imp_2，不用欄位名稱，中文和符號不會出錯",
        "score 從 0 起算，4 個選項回 0 到 3",
        "另問一題 chart_count，換算要畫幾張圖",
    ],
    "notes": "敏感欄位只送名稱和型別，相異值數量和樣本值整個不送。chart_count 的分數四捨五入再加一就是張數 K，夾在 1 到 6 之間。",
}

GUARD = {
    "title": "信心不夠或型別對不上，一律當成 ignore",
    "start": "JEV 回答角色",
    "check1": "confidence 低於 0.5？",
    "check2": "measure 不是數字、time 不是日期？",
    "ignore": "改判 ignore",
    "accept": "採用這個角色",
    "yes": "是",
    "no": "否",
    "bottom": "JEV 可能誤判，但文字欄位不會被拿去加總",
    "notes": "這是 ConfigAssembler.evaluate 的兩層防呆。第二輪選圖也一樣：答 none、信心低於 0.5，或選了清單外的圖型，那一組就跳過。",
}

LEGAL = {
    "title": "合法組合寫在表裡，表外的組合不會產生",
    "header": ("角色組合", "能畫的圖"),
    "rows": [
        ("time × measure", "折線圖、長條圖"),
        ("dimension × measure", "長條圖、圓餅圖（相異值 ≤ 8 才有圓餅圖）"),
        ("measure × measure", "散佈圖"),
        ("dimension × 3～8 個 measure", "雷達圖（相異值 ≤ 5）"),
        ("time 或 dimension × dimension × measure", "堆疊長條圖、熱力圖（相異值 ≤ 10 才有堆疊長條圖）"),
    ],
    "notes": "兩個 dimension 湊不出散佈圖，因為表裡只有 measure 配 measure 才給 scatter。明細表不經過 JEV，每份儀表板固定加一張。",
}

AVERAGE = {
    "title": "組合分數取平均，兩個好欄位才贏得過三個普通欄位",
    "categories": ["組合 A：金額＋訂單日期", "組合 B：三個次要欄位"],
    "series": [("加總", [5.6, 6.0]), ("平均", [2.8, 2.0])],
    "tag": "示意數字",
    "points": [
        "用加總的話，三欄組合多一個欄位就多一分，幾乎永遠排前面",
        "用平均，真正重要的兩欄組合還有機會贏",
        "排序後取分數最高的 2K 組進第二輪",
    ],
    "notes": "A 的分數來自文件範例，金額 3.0、訂單日期 2.6。B 是示意。同分時照產生順序，先兩欄組合，再雷達，再三欄組合。",
}

FUNNEL = {
    "title": "兩輪問答把 1225 組可能壓到最多 12 題",
    "kpis": [
        ("1225", "50 個欄位的兩兩組合"),
        ("8", "第一輪篩完剩下的候選欄位"),
        ("12", "第二輪最多問的題數（2K，K ≤ 6）"),
    ],
    "bottom": "不管原始資料有幾欄，題數上限都一樣",
    "notes": "第一輪先把欄位篩到最多 8 個候選，組合還沒爆炸就減量。第二輪只問分數最高的 2K 組，K 最多 6。",
}

PRIVACY = {
    "title": "送給 JEV 的只有欄位資訊和摘要，資料列一筆都不送",
    # (icon 檔名, 標題, 說明)；最後一格是強調格
    "cells": [
        ("table", "欄位資訊", "名稱、型別、相異值數量、5 筆樣本"),
        ("intersect", "組合描述", "欄位名稱和角色，不含任何值"),
        ("pulse", "異常摘要", "兩期聚合值和變化百分比"),
        ("chat-text", "使用者需求", "需求句子和目前圖表的標題"),
        ("git-merge", "合併來源", "來源名稱、筆數和欄位清單"),
        ("prohibit", "永遠不送", "原始資料列"),
    ],
    "caption": "勾成敏感的欄位，只送名稱和型別",
    "notes": "錯誤也一樣處理。JEV 或外部 API 失敗時，回給使用者的 502 只帶狀態碼，不轉出上游回應內容。外部系統的 secret 只認 DS_ 開頭的環境變數，呼叫當下才讀。",
}

REUSE = {
    "title": "同一套分工已經用在四個地方",
    "header": ("場景", "JEV 回答", "後端決定"),
    "rows": [
        ("產生儀表板", "欄位重要性、角色、圖型", "合法組合、mapping、版面"),
        ("依需求調整", "欄位相不相關、圖要不要留", "焦點排序、保留與補位、新圖編號"),
        ("載入時異常檢查", "有沒有異常、嚴重程度", "檢查哪些卡片、比哪兩期、快取"),
        ("合併時對欄位", "哪兩個欄位是同一種資料", "候選範圍、採用門檻、衝突取捨"),
    ],
    "notes": "依需求分析時，JEV 多回答 rel_i 和 keep_<chartId> 兩種題目，合併對欄位用 map_k_j。題目變了，分工沒變：哪些要問、候選有哪些、答案採不採用，都是後端規則。",
}

ACTIONS = {
    "title": "套到自己專案：先列題目，再寫規則",
    # (行動, 回看第幾頁, 那頁的短名)
    "items": [
        ("把要模型做的事，改寫成 score 和 choice 題目", 5, "結構化題目"),
        ("合法性、彙總、版面寫成後端純函式，能寫單元測試", 7, "合法組合表"),
        ("只送欄位資訊和摘要，失敗時不影響已經做好的部分", 10, "送出的內容"),
    ],
    "footer": "完整規則見 docs/JEV智慧化產製儀表板的流程與方法.md",
    "notes": "三件事照順序做。先列題目，題目的選項本身就是規則的邊界。",
}

FOOTER_LABEL = "JEV 智慧化產製儀表板"

if __name__ == "__main__":
    # 自我檢查：沒有殘留 emoji、每頁都有講者備忘
    import unicodedata
    pages = [COVER, PROBLEMS, SPLIT, FLOW, QUESTIONS, GUARD, LEGAL, AVERAGE, FUNNEL, PRIVACY, REUSE, ACTIONS]
    assert len(pages) == 12
    assert all(p["notes"] for p in pages)
    text = repr(pages)
    assert not [c for c in text if unicodedata.category(c) == "So" and ord(c) > 0x2700], "emoji 殘留"
    print("content OK")
