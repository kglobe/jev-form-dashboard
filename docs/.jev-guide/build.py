"""Build the standalone JEV guide. Requires Python-Markdown (`pip install markdown`)."""

from __future__ import annotations

import html
import re
from pathlib import Path

import markdown


DOCS = Path(__file__).resolve().parents[1]
SOURCE = DOCS / "JEV智慧化產製儀表板的流程與方法.md"
DIAGRAM = Path(__file__).with_name("archify-diagram.html")
OUTPUT = DOCS / "JEV儀表板完整指南.html"


def build() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    source = re.sub(r"^# .+\n", "", source, count=1)
    source, count = re.subn(r"```mermaid\n.*?\n```", "ARCHIFY_FIGURE", source, count=1, flags=re.S)
    if count != 1:
        raise ValueError("找不到來源文件的 Mermaid 流程圖")

    article = markdown.Markdown(extensions=["extra", "toc", "fenced_code", "tables"]).convert(source)
    figure = (
        '<div class="figure"><iframe title="JEV 系統架構互動圖" '
        f'srcdoc="{html.escape(DIAGRAM.read_text(encoding="utf-8"), quote=True)}"></iframe></div>'
        '<p class="caption">可在圖中縮放、切換主題及查看節點來源；下文保留完整規則。</p>'
    )
    article = article.replace("<p>ARCHIFY_FIGURE</p>", figure)
    if "ARCHIFY_FIGURE" in article:
        raise ValueError("互動圖未成功嵌入")

    headings = re.findall(r'<h2 id="([^"]+)">(.+?)</h2>', article)
    if len(headings) != 15:
        raise ValueError(f"預期 15 個主題，實際找到 {len(headings)} 個")
    nav = "\n".join(
        f'<a href="#{html.escape(anchor, quote=True)}">{html.escape(re.sub(r"<[^>]+>", "", label))}</a>'
        for anchor, label in headings
    )

    page = r'''<!doctype html>
<html lang="zh-Hant">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="JEV 儀表板操作、兩輪分析、系統架構、資料轉換與異常檢查完整指南。">
  <title>JEV 儀表板完整指南</title>
  <style>
    :root { --paper:#f6f5f0; --ink:#1b3334; --muted:#53686a; --line:#cfdcd6; --teal:#006b64; --rust:#a75230; --white:#fffefa; }
    * { box-sizing:border-box; }
    html { scroll-behavior:smooth; }
    body { margin:0; background:var(--paper); color:var(--ink); font:16px/1.78 "Noto Sans TC","Microsoft JhengHei",sans-serif; }
    a { color:var(--teal); text-underline-offset:3px; }
    a:hover { color:var(--rust); }
    .layout { display:grid; grid-template-columns:248px minmax(0,1fr); min-height:100vh; }
    aside { position:sticky; top:0; align-self:start; height:100vh; overflow:auto; padding:26px 20px; background:#eaf0ea; border-right:1px solid var(--line); }
    aside .logo { display:block; margin-bottom:24px; color:var(--ink); font:700 24px/1.1 Georgia,"Noto Serif TC",serif; text-decoration:none; }
    aside .logo small { display:block; margin-top:7px; color:var(--teal); font:700 10px/1.4 Consolas,monospace; letter-spacing:.17em; }
    aside nav { display:grid; gap:2px; }
    aside nav a { padding:6px 8px; border-radius:4px; color:#395154; font-size:12px; line-height:1.45; text-decoration:none; }
    aside nav a:hover, aside nav a:focus-visible { color:#064f4a; background:#d9e8dd; }
    .nav-label { margin:22px 0 7px; color:#6f8580; font:700 10px/1.4 Consolas,monospace; letter-spacing:.13em; }
    main { min-width:0; }
    .hero { background:#103b39; color:#fffefa; border-bottom:5px solid #d3a071; padding:55px clamp(24px,5.4vw,82px) 48px; }
    .hero-inner,.content { max-width:1100px; margin:auto; }
    .eyebrow { color:#a8d5c7; font:700 11px/1.4 Consolas,monospace; letter-spacing:.16em; }
    h1,h2.hero-title,.article h2 { font-family:Georgia,"Noto Serif TC","PMingLiU",serif; letter-spacing:-.03em; }
    h1 { max-width:820px; margin:14px 0; font-size:clamp(35px,4vw,60px); line-height:1.18; }
    .hero p { max-width:780px; margin:0; color:#dcefe8; font-size:clamp(16px,1.5vw,20px); }
    .hero-tags { display:flex; flex-wrap:wrap; gap:8px; margin-top:26px; }
    .hero-tags span { border:1px solid #5b8b81; padding:5px 9px; color:#d9f1e7; font:700 11px/1.4 Consolas,monospace; }
    .mobile-nav { display:none; }
    .content { padding:35px clamp(24px,5.4vw,82px) 90px; }
    .brief { display:grid; grid-template-columns:repeat(3,1fr); gap:14px; margin-bottom:48px; }
    .brief section { background:var(--white); border:1px solid var(--line); border-top:4px solid var(--teal); padding:21px 21px 16px; box-shadow:0 4px 0 #e7ece5; }
    .brief section:nth-child(2) { border-top-color:var(--rust); }
    .brief section:nth-child(3) { border-top-color:#5d6b9e; }
    .brief .no { display:block; color:var(--teal); font:700 11px/1.4 Consolas,monospace; letter-spacing:.12em; }
    .brief h2 { margin:8px 0 11px; font:700 24px/1.3 Georgia,"Noto Serif TC",serif; }
    .brief p { margin:0; font-size:14px; }
    .example { background:#e8f1ec; border-left:4px solid var(--teal); padding:25px 28px; margin-bottom:53px; }
    .example h2 { margin:0 0 12px; font:700 28px/1.35 Georgia,"Noto Serif TC",serif; }
    .example ol { margin:0; padding-left:23px; }
    .example li { margin:7px 0; }
    .example .note { margin:14px 0 0; font-size:14px; color:var(--muted); }
    .article { min-width:0; }
    .article h2 { margin:67px 0 19px; padding-top:11px; border-top:1px solid var(--line); font-size:clamp(27px,2.5vw,37px); line-height:1.3; scroll-margin-top:24px; }
    .article h3 { margin:31px 0 12px; font-size:21px; scroll-margin-top:24px; }
    .article p { margin:0 0 16px; max-width:85ch; }
    .article ul,.article ol { max-width:88ch; margin:0 0 22px; padding-left:24px; }
    .article li { margin-bottom:8px; }
    .article strong { color:#123d3e; }
    code { padding:1px 5px; border-radius:3px; background:#e4ebe7; font:.88em/1.6 Consolas,monospace; overflow-wrap:anywhere; }
    pre { margin:18px 0 26px; padding:20px 22px; overflow:auto; background:#172e32; color:#eff5ef; border-left:4px solid #79b5a5; font-size:13px; }
    pre code { padding:0; background:none; color:inherit; overflow-wrap:normal; }
    table { display:block; width:100%; overflow-x:auto; margin:20px 0 28px; border-collapse:collapse; background:var(--white); font-size:14px; }
    th,td { min-width:130px; border:1px solid var(--line); padding:9px 12px; text-align:left; vertical-align:top; }
    th { background:#dcece5; color:#164b49; white-space:nowrap; }
    tr:nth-child(even) td { background:#f0f4ef; }
    .figure { margin-top:21px; border:1px solid var(--line); background:white; box-shadow:0 9px 26px #183f331a; }
    .figure iframe { display:block; width:100%; height:1080px; border:0; }
    .caption,.foot { color:var(--muted); font-size:13px; }
    .foot { margin-top:78px; padding-top:18px; border-top:1px solid var(--line); }
    @media(max-width:920px) {
      .layout { display:block; }
      aside { position:relative; height:auto; padding:17px 23px; border-right:0; border-bottom:1px solid var(--line); }
      aside nav,.nav-label { display:none !important; }
      aside .logo { margin:0; }
      .mobile-nav { display:block; padding:12px 23px; background:#eaf0ea; border-bottom:1px solid var(--line); }
      .mobile-nav summary { color:var(--teal); font-weight:700; cursor:pointer; }
      .mobile-nav nav { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:7px 11px; margin-top:13px; }
      .mobile-nav a { font-size:13px; text-decoration:none; }
      .hero { padding-top:40px; }
      .brief { grid-template-columns:1fr; }
      .figure iframe { height:850px; }
    }
  </style>
</head>
<body>
<div class="layout">
  <aside aria-label="章節導覽">
    <a class="logo" href="#top">JEV Dashboard<small>SYSTEM FIELD GUIDE</small></a>
    <div class="nav-label">先看這裡</div>
    <nav><a href="#journey">操作與分工</a><a href="#worked-example">訂單資料範例</a></nav>
    <div class="nav-label">完整說明</div>
    <nav>@@NAV@@</nav>
  </aside>
  <main id="top">
    <header class="hero"><div class="hero-inner">
      <div class="eyebrow">JEV DASHBOARD / ARCHITECTURE & METHOD</div>
      <h1>資料如何變成儀表板</h1>
      <p>從上傳資料、JEV 兩輪判斷，到後端組裝圖表與載入後的異常檢查。先看操作與範例，再依章節查完整規則。</p>
      <div class="hero-tags"><span>互動架構圖</span><span>15 個主題</span><span>離線可讀</span></div>
    </div></header>
    <details class="mobile-nav"><summary>查看章節</summary><nav>@@NAV@@</nav></details>
    <div class="content">
      <div class="brief" id="journey">
        <section><span class="no">01 / 操作</span><h2>先準備資料</h2><p>在「資料」匯入 CSV／Excel，或由管理員註冊外部 API。確認欄位型別，先標記敏感欄位。接著到「製圖」選來源、勾選欄位或寫一句需求，再按「分析」。</p></section>
        <section><span class="no">02 / JEV</span><h2>回答結構化題目</h2><p>第一輪評估欄位重要性、角色與建議張數；第二輪只從後端提供的合法圖型或 <code>none</code> 中選擇。JEV 不直接產生儀表板配置。</p></section>
        <section><span class="no">03 / 後端</span><h2>決定能畫什麼</h2><p>後端排除低信心與型別不符的答案，產生 <code>mapping</code>、<code>gridPos</code> 和明細表。儲存後再次載入，直接用原配置畫圖，再非同步檢查異常。</p></section>
      </div>
      <section class="example" id="worked-example">
        <h2>用訂單資料走一次</h2>
        <ol>
          <li>匯入「訂單日期、地區、金額」三欄。型別推斷為日期、文字、數字。</li>
          <li>第一輪 JEV 可把三欄判為時間、分類、數值，並回答建議張數 <code>K</code>。後端依重要性挑候選，列出合法欄位組合。</li>
          <li>例如「訂單日期 × 金額」可選折線或長條；「地區 × 金額」可選長條，地區相異值不超過 8 時也可選圓餅。</li>
          <li>第二輪 JEV 為各組選圖型或 <code>none</code>。後端檢查信心與合法性，最多取 <code>K</code> 張，最後固定加明細表。</li>
        </ol>
        <p class="note">這是流程示例，實際選圖取決於資料摘要與 JEV 回答；相同資料與相同回答會組出相同配置。</p>
      </section>
      <article class="article" aria-label="完整技術說明">@@ARTICLE@@</article>
      <footer class="foot">內容依 <code>docs/JEV智慧化產製儀表板的流程與方法.md</code> 整理，互動圖由 Archify 產生。原始資料列不送給 JEV；敏感欄位只送名稱與型別。</footer>
    </div>
  </main>
</div>
</body>
</html>'''
    page = page.replace("@@NAV@@", nav).replace("@@ARTICLE@@", article)
    OUTPUT.write_text(page, encoding="utf-8")
    print(f"{OUTPUT} ({OUTPUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    build()
