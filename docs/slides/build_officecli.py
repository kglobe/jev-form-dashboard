"""用 OfficeCLI 產生 JEV 儀表板簡報（規格見 brief.md，文案取自 content.py）。

執行：python build_officecli.py
文件內容全部由 officecli 指令產生（add / set / batch / raw-set）；Python 只負責組指令、讀 content.py。
暫存（batch JSON、上色後的 SVG、zoom 縮圖）放在 OC_TMP 或系統暫存目錄。
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True  # 不在 docs/slides 留 __pycache__
sys.path.insert(0, str(HERE))
import content as C  # noqa: E402

OUT = HERE / "JEV儀表板_officecli.pptx"
TMP = Path(os.environ.get("OC_TMP") or Path(tempfile.gettempdir()) / "jev_officecli")
TMP.mkdir(parents=True, exist_ok=True)

# ---- 色票、字級、字型 ----
BG = "1F1C5C-5B1F6E-45"
INK, MUTE, LINE, ACC, ACC_INK = "FFFFFF", "D6D3F0", "B8B4E0", "00F2FE", "1F1C5C"
ORB = {"violet": ("667EEA", 0.55), "pink": ("F093FB", 0.45), "cyan": ("00F2FE", 0.30)}
DISPLAY, H1, H2, BODY, CAP, CODE = 54, 26, 16, 14, 10.5, 11
FONT = "Noto Sans TC"
TEXT_SHADOW = "000000-4-90-1.5-35"

ORBS = {  # 頁: (violet, pink, cyan)，每個 (cx, cy, d)
    1: ((8.3, 1.0, 4.4), (6.4, 5.2, 3.6), (0.9, 5.1, 1.8)),
    2: ((9.7, 4.9, 3.6), (0.3, 0.3, 3.2), (5.0, 5.7, 1.6)),
    3: ((0.2, 5.3, 3.4), (9.8, 0.2, 3.6), (5.0, 0.0, 1.4)),
    4: ((5.0, 6.0, 4.0), (-0.2, 2.8, 3.0), (10.0, 2.8, 1.8)),
    5: ((9.9, 0.6, 3.4), (4.6, 6.0, 3.0), (0.0, 0.4, 1.6)),
    6: ((0.0, 0.2, 3.2), (10.0, 5.4, 3.8), (6.0, 5.9, 1.4)),
    7: ((10.1, 3.0, 3.6), (1.5, 6.0, 3.2), (4.8, -0.3, 1.6)),
    8: ((3.5, 6.1, 3.8), (9.9, 0.0, 3.2), (0.1, 2.6, 1.8)),
    9: ((5.0, -0.6, 4.2), (0.0, 5.6, 3.4), (9.9, 5.5, 1.8)),
    10: ((-0.3, 3.5, 3.4), (10.2, 1.8, 3.4), (7.0, 6.0, 1.4)),
    11: ((9.6, 5.8, 3.8), (2.0, -0.4, 3.0), (0.2, 4.8, 1.6)),
    12: ((8.0, 1.2, 4.2), (6.0, 5.4, 3.4), (1.0, 5.2, 1.8)),
}
MORPH = "morph-1200"
TRANSITION = {1: "fade", 4: "wipe-right", 7: "split-vertical-out", 9: "zoom", 11: "push-up"}  # 其餘 morph
LINKS = []
WIPE_FILTER = {"left": "8", "right": "2", "up": "1", "down": "4"}  # officecli 方向 → presetSubtype


def inch(v):
    return f"{round(v, 4)}in"


def oc(*args, check=True):
    r = subprocess.run(["officecli", *map(str, args)], capture_output=True, text=True, encoding="utf-8")
    if check and r.returncode != 0:
        raise RuntimeError(f"officecli {' '.join(map(str, args))}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def run_batch(cmds, tag):
    path = TMP / f"batch_{tag}.json"
    path.write_text(json.dumps(cmds, ensure_ascii=False), encoding="utf-8")
    out = oc("batch", OUT, "--input", path, check=False)
    m = re.search(r"(\d+) succeeded, (\d+) failed", out)
    if not m or m.group(2) != "0":
        bad = [l for l in out.splitlines() if re.search(r"error|fail|warn", l, re.I)]
        raise RuntimeError(f"batch {tag} failed:\n" + "\n".join(bad[:20]))


def recolor_svg(name, color):
    """SVG 的 fill 是 currentColor，換成實色（只改暫存的 icon 副本，不碰 pptx）。"""
    src = (HERE / "assets" / f"{name}.svg").read_text(encoding="utf-8")
    dst = TMP / f"{name}-{color}.svg"
    dst.write_text(src.replace('fill="currentColor"', f'width="256" height="256" fill="#{color}"'), encoding="utf-8")
    return str(dst)


class Slide:
    def __init__(self, n):
        self.n, self.k, self.cmds, self.fix = n, 0, [], []
        self.kind = {}  # id -> 'shape' | 'other'（非 shape 的動畫要借 dummy）
        self.dummy = {}  # 真目標 id -> dummy id
        self.floats, self.cid, self.direct = [], 9000, False
        self.used = set()
        self.cmds.append({"command": "add", "parent": "/", "type": "slide", "props": {
            "layout": "blank", "background": BG, "transition": TRANSITION.get(n, MORPH)}})
        for (cx, cy, d), (key, (color, alpha)) in zip(ORBS[n], ORB.items()):
            self.shape(cx - d / 2, cy - d / 2, d, d, name=f"!!orb-{key}", preset="ellipse", fill=color,
                       opacity=alpha, line="none", softEdge=round(d * 72 * 0.25, 2))

    @property
    def p(self):
        return f"/slide[{self.n}]"

    def add(self, typ, props, kind="shape"):
        self.k += 1
        sid = self.n * 1000 + self.k
        if typ != "zoom":
            props = {"id": sid, **props}
        self.cmds.append({"command": "add", "parent": self.p, "type": typ, "props": props})
        self.kind[sid] = kind
        return sid

    def shape(self, x, y, w, h, **props):
        if "text" in props:
            props["lang"] = "zh-TW"
        return self.add("shape", {"x": inch(x), "y": inch(y), "width": inch(w), "height": inch(h), **props})

    def text(self, x, y, w, h, text, size, color=INK, bold=False, align=None, valign=None, shadow=False, **kw):
        props = {"text": text, "font": FONT, "size": size, "color": color, "bold": str(bold).lower(), **kw}
        if align:
            props["align"] = align
        if valign:
            props["valign"] = valign
        if shadow:
            props["shadow"] = TEXT_SHADOW
        return self.shape(x, y, w, h, **props)

    def para(self, sid, text, size, color=INK, bold=False, align=None):
        props = {"text": text, "font": FONT, "size": size, "color": color, "bold": str(bold).lower(), "lang": "zh-TW"}
        if align:
            props["align"] = align
        self.cmds.append({"command": "add", "parent": f"{self.p}/shape[@id={sid}]", "type": "paragraph", "props": props})

    def card(self, x, y, w, h, style="glass", preset="roundRect", **kw):
        props = {"preset": preset}
        if preset == "roundRect":
            props["adj"] = f"adj:val {round(0.12 / min(w, h) * 100000)}"
        if style == "glass":
            props.update(fill="FFFFFF", opacity=0.12, line="FFFFFF:0.75", lineOpacity=0.35, shadow="000000-18-90-6-25")
        elif style == "hot":
            props.update(fill=ACC, opacity=0.20, line=f"{ACC}:1.25", shadow="000000-18-90-6-25", glow=f"{ACC}-8-45")
        elif style == "code":
            props.update(fill="0F0D2E", opacity=0.55, line="FFFFFF:0.75", lineOpacity=0.25)
        return self.shape(x, y, w, h, **props, **kw)

    def badge(self, x, y, d, text, size):
        return self.shape(x, y, d, d, preset="ellipse", fill="none", line=f"{ACC}:1.25", text=text, font=FONT,
                          size=size, bold="true", color=ACC, align="center", valign="middle", margin=0)

    def title(self, text):
        return self.text(0.5, 0.4, 9.0, 0.9, text, H1, bold=True, valign="middle", shadow=True)

    def footer(self, label=C.FOOTER_LABEL, box=(0.5, 5.0, 5.0, 0.25)):
        self.text(*box, label, CAP, MUTE)
        self.text(8.5, 5.0, 1.0, 0.25, f"{self.n:02d}", CAP, MUTE, align="right")

    def connector(self, a, b, fa, fb, ia, ib, shape="straight", **geom):
        props = {"from": f"{self.p}/shape[@id={a}]", "to": f"{self.p}/shape[@id={b}]", "shape": shape,
                 "color": LINE, "lineWidth": 1.25, "tailEnd": "triangle", "fromSide": fa, "toSide": fb,
                 "fromIdx": ia, "toIdx": ib}
        props.update({k: inch(v) for k, v in geom.items()})
        return self.add("connector", props, kind="other")

    def notes(self, text):
        self.cmds.append({"command": "add", "parent": self.p, "type": "notes", "props": {"text": text}})

    # ---- 動畫 ----
    def anim(self, target, effect, trigger, cls="entrance", dur=400, direction=None, delay=None, **extra):
        if self.kind.get(target) == "other":  # 連接線、圖片、表格、zoom：officecli 只能掛在 shape/chart 上
            if target not in self.dummy:
                self.dummy[target] = self.shape(0, 0, 0.1, 0.1, name=f"tmp-{target}")
            host = self.dummy[target]
        else:
            host, self.direct = target, True
        typ = "chart" if self.kind.get(target) == "chart" else "shape"
        props = {"class": cls, "trigger": trigger, "duration": dur, **extra}
        if cls != "motion":
            props["effect"] = effect
        if direction:
            props["direction"] = direction
        if delay:
            props["delay"] = delay
        self.cmds.append({"command": "add", "parent": f"{self.p}/{typ}[@id={host}]", "type": "animation", "props": props})
        self.used.add(effect if effect != "wipe" else f"wipe-{direction}")
        if effect == "float":
            self.floats.append(target)

    def raw(self, xpath, action, xml=None):
        c = {"command": "raw-set", "part": self.p, "xpath": xpath, "action": action}
        if xml:
            c["xml"] = xml
        self.fix.append(c)

    def finish(self):
        # 1) dummy 上的動畫改指向真目標，再移除 dummy 與它的 bldP
        for real, d in self.dummy.items():
            self.raw(f"//p:spTgt[@spid='{d}']", "setattr", f"spid={real}")
            self.raw(f"//p:bldP[@spid='{d}']", "remove")
            self.raw(f"//p:sp[p:nvSpPr/p:cNvPr/@id='{d}']", "remove")
        if self.dummy and not self.direct:  # 只剩 dummy 的 bldP 被拿掉後，空的 bldLst 不合 schema
            self.raw("//p:bldLst", "remove")
        # 2) officecli 寫錯的 presetID 改回 PowerPoint 的值
        ent = "@presetClass='entr'"
        for d, st in WIPE_FILTER.items():
            if f"wipe-{d}" in self.used:  # officecli 不管方向都寫 wipe(left)
                self.raw(f"//p:cTn[@presetID='20' and {ent} and @presetSubtype='{st}']//p:animEffect", "setattr", f"filter=wipe({d})")
        if any(u.startswith("wipe-") for u in self.used):
            self.raw(f"//p:cTn[@presetID='20' and {ent}]", "setattr", "presetID=22")  # 20 = Wedge
        if "zoom" in self.used:
            self.raw(f"//p:cTn[@presetID='21' and {ent}]", "setattr", "presetSubtype=16")
            self.raw(f"//p:cTn[@presetID='21' and {ent}]", "setattr", "presetID=53")  # 21 = Wheel
        if "split" in self.used:
            self.raw(f"//p:cTn[@presetID='15' and {ent}]", "setattr", "presetSubtype=26")
            self.raw(f"//p:cTn[@presetID='15' and {ent}]", "setattr", "presetID=16")  # 15 = Spiral In
        for t in self.floats:  # officecli 的 float 是 Peek（從投影片外飛入），改成 Float In：上移 10% + 淡入
            ctn = f"//p:cTn[@presetID='12' and {ent}][.//p:spTgt/@spid='{t}']"
            self.cid += 1
            self.raw(f"{ctn}//p:strVal[@val='1+#ppt_h/2']", "setattr", "val=#ppt_y+.1")
            self.raw(f"{ctn}/p:childTnLst", "append",
                     f'<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="{self.cid}" dur="400"/>'
                     f'<p:tgtEl><p:spTgt spid="{t}"/></p:tgtEl></p:cBhvr></p:animEffect>')
            self.raw(ctn, "setattr", "presetID=42")
        if "growShrink" in self.used:  # officecli 固定放大 150%
            for a in ("x", "y"):
                self.raw("//p:cTn[@presetID='6' and @presetClass='emph']//p:animScale/p:by", "setattr", f"{a}=115000")
        if any(c["props"].get("class") == "motion" for c in self.cmds if c.get("type") == "animation"):
            self.raw("//p:cTn[@presetClass='path']", "setattr", "presetID=0")  # 自訂路徑
        run_batch(self.cmds, f"s{self.n:02d}")
        if self.fix:
            run_batch(self.fix, f"s{self.n:02d}_fix")


# ---------------------------------------------------------------- 各頁
def s1():
    s, d = Slide(1), C.COVER
    card = s.card(0.35, 1.2, 7.2, 3.55)
    s.text(0.7, 1.45, 6.6, 0.35, d["kicker"], H2, ACC, bold=True, spacing=3)
    # 行距 = 1.1 倍字級（59.4pt 固定值）；用 1.1x 倍數在 Noto Sans TC 會超出 1.75in 框、壓到副標
    t = s.text(0.7, 1.85, 6.6, 1.75, "\n".join(d["title"]), DISPLAY, bold=True, shadow=True,
               lineSpacing=f"{DISPLAY * 1.1:.1f}pt")
    sub = s.text(0.7, 3.7, 6.6, 0.4, d["subtitle"], H2, MUTE, bold=True)
    meta = s.text(0.7, 4.2, 6.6, 0.3, d["meta"], CAP, MUTE)
    s.notes(d["notes"])
    s.anim(card, "fade", "afterPrevious", dur=500)
    s.anim(t, "zoom", "afterPrevious", dur=600)
    s.anim(sub, "wipe", "afterPrevious", direction="left")
    s.anim(meta, "fade", "afterPrevious")
    s.finish()


def s2():
    s, d = Slide(2), C.PROBLEMS
    s.title(d["title"])
    groups = []
    for i, (head, body) in enumerate(d["cards"]):
        x, y = (0.5, 3.6, 6.7)[i], 1.6
        g = [s.card(x, y, 2.8, 2.9, "hot" if i == 2 else "glass"),
             s.text(x + 0.25, y + 0.25, 1.0, 0.4, f"{i + 1:02d}", H2, ACC, bold=True),
             s.text(x + 0.25, y + 0.8, 2.3, 0.45, head, H2, bold=True),
             s.text(x + 0.25, y + 1.35, 2.3, 1.35, body, BODY, MUTE, lineSpacing="1.3x")]
        groups.append(g)
    s.footer()
    s.notes(d["notes"])
    for g in groups:
        for j, sid in enumerate(g):
            s.anim(sid, "fly", "onClick" if j == 0 else "withPrevious", direction="down")
    for j, sid in enumerate(groups[2]):  # 卡和卡內文字一起搖，只搖卡會跟文字錯開
        s.anim(sid, "teeter", "afterPrevious" if j == 0 else "withPrevious", cls="emphasis")
    s.finish()


def s3():
    s, d = Slide(3), C.SPLIT
    s.title(d["title"])
    cols = []
    for (head, items), x, hc, dc in ((d["left"], 0.5, ACC, ACC), (d["right"], 5.15, INK, MUTE)):
        g = [s.card(x, 1.55, 4.35, 2.75), s.text(x + 0.3, 1.75, 3.8, 0.4, head, H2, hc, bold=True)]
        for i, item in enumerate(items):
            y = 2.3 + 0.48 * i
            g.append(s.shape(x + 0.3, y + 0.15, 0.1, 0.1, preset="ellipse", fill=dc, line="none"))
            g.append(s.text(x + 0.5, y, 3.65, 0.4, item, BODY, valign="middle"))
        cols.append(g)
    bottom = s.text(0.5, 4.5, 9.0, 0.4, d["bottom"], BODY, ACC, bold=True)
    s.footer()
    s.notes(d["notes"])
    for g, direction in zip(cols, ("left", "right")):
        for j, sid in enumerate(g):
            s.anim(sid, "wipe", "onClick" if j == 0 else "withPrevious", direction=direction)
    s.anim(bottom, "zoom", "onClick")
    s.anim(bottom, "pulse", "afterPrevious", cls="emphasis")
    s.finish()


def s4():
    s, d = Slide(4), C.FLOW
    s.title(d["title"])
    xs = (0.5, 2.867, 5.233, 7.6)
    nodes, jev = [], []
    for i, (label, is_jev, page) in enumerate(d["nodes"]):
        x, y = (xs[i], 1.65) if i < 4 else (xs[7 - i], 3.25)
        sid = s.card(x, y, 1.9, 0.8, "hot" if is_jev else "glass", text=f"{i + 1:02d}", font=FONT, size=CAP,
                     color=ACC, align="center", valign="middle", margin=0)
        if page:  # 跳頁超連結只能指向已存在的頁，全部建完再補
            LINKS.append({"command": "set", "path": f"{s.p}/shape[@id={sid}]", "props": {"link": f"slide[{page}]"}})
        s.para(sid, label, 13, INK, bold=True, align="center")
        nodes.append(sid)
        if is_jev:
            jev.append(sid)
    lines = []
    for i in range(7):
        a, b = nodes[i], nodes[i + 1]
        if i < 3:
            lines.append(s.connector(a, b, "right", "left", 3, 1))
        elif i == 3:
            lines.append(s.connector(a, b, "bottom", "top", 2, 0))
        else:
            lines.append(s.connector(a, b, "left", "right", 1, 3))
    s.text(0.5, 4.4, 9.0, 0.35, d["caption"], BODY, MUTE)
    s.footer()
    s.notes(d["notes"])
    s.anim(nodes[0], "fade", "afterPrevious", dur=300)
    for ln, node in zip(lines, nodes[1:]):
        s.anim(ln, "fade", "afterPrevious", dur=300)
        s.anim(node, "fade", "withPrevious", dur=300)
    for j, sid in enumerate(jev):
        s.anim(sid, "pulse", "onClick" if j == 0 else "withPrevious", cls="emphasis")
    s.finish()


def s5():
    s, d = Slide(5), C.QUESTIONS
    s.title(d["title"])
    ask = [s.card(0.5, 1.55, 5.7, 1.85, "code"),
           s.text(0.7, 1.65, 0.5, 0.25, d["ask_label"], CAP, ACC),
           s.text(0.7, 1.9, 5.3, 1.45, "\n".join(d["ask_code"]), CODE, INK, **{"font.latin": "Consolas"})]
    ans = [s.card(0.5, 3.6, 5.7, 1.25, "code"),
           s.text(0.7, 3.7, 0.5, 0.25, d["answer_label"], CAP, ACC),
           s.text(0.7, 3.95, 5.3, 0.8, "\n".join(d["answer_code"]), CODE, INK, **{"font.latin": "Consolas"})]
    pts = []
    for i, (y, txt) in enumerate(zip((1.6, 2.65, 3.7), d["points"])):
        pts.append((s.badge(6.5, y, 0.36, str(i + 1), CAP), s.text(7.0, y - 0.05, 2.5, 0.95, txt, BODY)))
    dot = s.shape(3.35 - 0.07, 3.4 - 0.07, 0.14, 0.14, preset="ellipse", fill=ACC, line="none", glow=f"{ACC}-8-45")
    s.footer()
    s.notes(d["notes"])
    for j, sid in enumerate(ask):
        s.anim(sid, "fade", "afterPrevious" if j == 0 else "withPrevious")
    s.anim(dot, "appear", "onClick")
    s.anim(dot, "custom", "withPrevious", cls="motion", dur=800, path="custom", d=f"M 0 0 L 0 {0.2 / 5.625:.4f} E")
    for j, sid in enumerate(ans):
        s.anim(sid, "split", "afterPrevious" if j == 0 else "withPrevious")
    s.anim(dot, "fade", "afterPrevious", cls="exit")
    for b, t in pts:
        s.anim(b, "float", "onClick")
        s.anim(t, "float", "withPrevious")
    s.finish()


def s6():
    s, d = Slide(6), C.GUARD
    s.title(d["title"])
    start = s.card(0.5, 2.0, 1.6, 0.9, text=d["start"], font=FONT, size=BODY, color=INK, align="center", valign="middle")
    hexa = dict(preset="hexagon", font=FONT, size=13, color=INK, align="center", valign="middle", margin=0)
    c1 = s.card(2.5, 1.95, 2.3, 1.0, text=d["check1"], **hexa)
    c2 = s.card(5.2, 1.95, 2.5, 1.0, text=d["check2"], **hexa)
    acc = s.card(8.0, 2.0, 1.5, 0.9, text=d["accept"], font=FONT, size=BODY, color=INK, align="center", valign="middle")
    ign = s.card(2.9, 3.55, 4.4, 0.75, "hot", text=d["ignore"], font=FONT, size=H2, bold="true", color=INK,
                 align="center", valign="middle")
    l0 = s.connector(start, c1, "right", "left", 3, 3)
    l_no1 = s.connector(c1, c2, "right", "left", 0, 3)
    l_no2 = s.connector(c2, acc, "right", "left", 0, 1)
    l_yes1 = s.connector(c1, ign, "bottom", "top", 2, 0, x=3.65, y=2.95, width=0, height=0.6)
    l_yes2 = s.connector(c2, ign, "bottom", "top", 2, 0, x=6.45, y=2.95, width=0, height=0.6)
    lab = dict(size=CAP, color=MUTE, margin=0)
    t_no1 = s.text(4.85, 2.12, 0.3, 0.25, d["no"], align="center", **lab)
    t_no2 = s.text(7.7, 2.12, 0.3, 0.25, d["no"], align="center", **lab)
    t_yes1 = s.text(3.75, 3.12, 0.3, 0.25, d["yes"], **lab)
    t_yes2 = s.text(6.55, 3.12, 0.3, 0.25, d["yes"], **lab)
    bottom = s.text(0.5, 4.55, 9.0, 0.35, d["bottom"], BODY, ACC, bold=True)
    s.footer()
    s.notes(d["notes"])
    s.anim(start, "fade", "onClick")
    s.anim(l0, "wipe", "onClick", direction="left")
    s.anim(c1, "fade", "withPrevious")
    s.anim(l_yes1, "wipe", "onClick", direction="up")
    s.anim(t_yes1, "appear", "withPrevious")
    s.anim(ign, "dissolve", "withPrevious")
    s.anim(l_no1, "wipe", "onClick", direction="left")
    s.anim(t_no1, "appear", "withPrevious")
    s.anim(c2, "fade", "withPrevious")
    s.anim(l_yes2, "wipe", "onClick", direction="up")
    s.anim(t_yes2, "appear", "withPrevious")
    s.anim(l_no2, "wipe", "onClick", direction="left")
    s.anim(t_no2, "appear", "withPrevious")
    s.anim(acc, "fade", "withPrevious")
    s.anim(bottom, "fade", "onClick")
    s.finish()


def s7():
    s, d = Slide(7), C.LEGAL
    s.title(d["title"])
    rows = [d["header"], *d["rows"]]
    tb = s.add("table", {"x": inch(0.5), "y": inch(1.55), "width": inch(9.0), "height": inch(3.1), "rows": len(rows),
                         "cols": 2, "colWidths": "4in,5in", "style": "none", "border.all": "none"}, kind="other")
    for r, row in enumerate(rows, 1):
        for c, txt in enumerate(row, 1):
            props = {"text": txt, "font": FONT, "valign": "middle"}
            if r == 1:
                props.update(fill=ACC, color=ACC_INK, size=H2, bold="true")
            else:
                props.update(fill="FFFFFF", opacity=0.08 if r % 2 == 0 else 0.14, color=INK, size=BODY)
            s.cmds.append({"command": "set", "path": f"{s.p}/table[@id={tb}]/tr[{r}]/tc[{c}]", "props": props})
    s.cmds.append({"command": "set", "path": f"{s.p}/table[@id={tb}]",
                   "props": {"border.horizontal": "0.75pt solid 40FFFFFF", "border.bottom": "0.75pt solid 40FFFFFF"}})
    s.footer()
    s.notes(d["notes"])
    s.anim(tb, "blinds", "afterPrevious")
    s.finish()


def s8():
    s, d = Slide(8), C.AVERAGE
    s.title(d["title"])
    (n1, v1), (n2, v2) = d["series"]
    ch = s.add("chart", {
        "chartType": "column", "x": inch(0.5), "y": inch(1.5), "width": inch(5.8), "height": inch(3.35),
        "categories": ",".join(d["categories"]),
        "series1.name": n1, "series1.values": ",".join(map(str, v1)), "series1.color": "B3FFFFFF",
        "series2.name": n2, "series2.values": ",".join(map(str, v2)), "series2.color": ACC,
        "title": "none", "legend": "top", "legendFont": f"11:{MUTE}:{FONT}", "axisfont": f"11:{MUTE}:{FONT}",
        "dataLabels": "outsideEnd", "labelfont": f"12:FFFFFF:{FONT}", "gridlines": "26FFFFFF:0.75",
        "chartareafill": "none", "plotFill": "none", "chartborder": "none"}, kind="chart")
    s.text(4.8, 1.5, 1.5, 0.25, d["tag"], CAP, MUTE, align="right")
    s.card(6.6, 1.55, 2.9, 3.3)
    pts = []
    for i, txt in enumerate(d["points"]):
        y = 1.8 + 1.0 * i
        pts.append((s.text(6.8, y + 0.03, 0.4, 0.25, f"{i + 1:02d}", CAP, ACC, margin=0),
                    s.text(7.15, y - 0.05, 2.2, 0.95, txt, BODY)))
    s.footer()
    s.notes(d["notes"])
    s.anim(ch, "wipe", "onClick", direction="down", chartBuild="category")
    for num, txt in pts:
        s.anim(txt, "fade", "afterPrevious")
        s.anim(num, "fade", "withPrevious")
    # 類別 2 改成接續類別 1（officecli 每個類別都是一次點擊）
    grp = "//p:par[p:cTn/p:childTnLst/p:par//a:chart/@categoryIdx='1'][parent::p:childTnLst/parent::p:cTn/@nodeType='mainSeq']"
    s.raw(f"{grp}/p:cTn/p:stCondLst/p:cond", "setattr", "delay=0")
    s.raw("//p:cTn[@nodeType='clickEffect'][.//a:chart/@categoryIdx='1']", "setattr", "nodeType=afterEffect")
    s.finish()


def s9():
    s, d = Slide(9), C.FUNNEL
    s.title(d["title"])
    cards = []
    for i, (num, desc) in enumerate(d["kpis"]):
        x = (0.5, 3.7, 6.9)[i]
        hot = i == 2
        extra = {"glow": f"{ACC}-8-45"} if hot else {}
        cards.append([s.card(x, 1.6, 2.6, 2.5, "hot" if hot else "glass"),
                      s.text(x, 1.85, 2.6, 1.0, num, DISPLAY, ACC if hot else INK, bold=True, align="center",
                             shadow=True, **extra),
                      s.text(x + 0.2, 3.0, 2.2, 0.9, desc, BODY, MUTE, align="center")])
    arrows = [s.shape(x, 2.65, 0.45, 0.35, preset="rightArrow", fill="FFFFFF", opacity=0.5, line="none")
              for x in (3.175, 6.375)]
    s.text(0.5, 4.4, 9.0, 0.4, d["bottom"], BODY, INK, bold=True)
    s.footer()
    s.notes(d["notes"])
    for i, g in enumerate(cards):
        for j, sid in enumerate(g):
            s.anim(sid, "zoom", "afterPrevious" if j == 0 else "withPrevious")
        if i < 2:
            s.anim(arrows[i], "wipe", "afterPrevious", direction="left")
    s.anim(cards[2][1], "growShrink", "onClick", cls="emphasis", autoReverse="true")
    s.finish()


def s10():
    s, d = Slide(10), C.PRIVACY
    s.title(d["title"])
    cells = []
    for i, (icon, head, desc) in enumerate(d["cells"]):
        x, y = (0.5, 3.6, 6.7)[i % 3], (1.55, 3.1)[i // 3]
        hot = i == 5
        g = [s.card(x, y, 2.8, 1.35, "hot" if hot else "glass")]
        g.append(s.add("picture", {"src": recolor_svg(icon, ACC if hot else INK), "x": inch(x + 0.25),
                                   "y": inch(y + 0.25), "width": inch(0.45), "height": inch(0.45), "alt": head},
                       kind="other"))
        g.append(s.text(x + 0.85, y + 0.27, 1.8, 0.4, head, H2, bold=True))
        g.append(s.text(x + 0.25, y + 0.78, 2.35, 0.5, desc, BODY, MUTE))
        cells.append(g)
    s.text(0.5, 4.6, 9.0, 0.3, d["caption"], BODY, MUTE)
    s.footer()
    s.notes(d["notes"])
    for i, g in enumerate(cells[:5]):
        for sid in g:
            s.anim(sid, "fade", "withPrevious", delay=150 * i or None)
    for j, sid in enumerate(cells[5]):
        s.anim(sid, "zoom", "onClick" if j == 0 else "withPrevious")
    for g in cells[:5]:
        for sid in g:
            s.anim(sid, "transparency", "withPrevious", cls="emphasis")
    s.finish()


def s11():
    s, d = Slide(11), C.REUSE
    s.title(d["title"])
    cols = ((0.7, 2.0), (2.9, 3.1), (6.2, 3.2))
    for (x, w), head, color in zip(cols, d["header"], (MUTE, ACC, MUTE)):
        s.text(x, 1.5, w, 0.4, head, CAP, color, bold=True, valign="middle")
    rows = []
    for y, (scene, jev, backend) in zip((1.95, 2.7, 3.45, 4.2), d["rows"]):
        rows.append([s.card(0.5, y, 9.0, 0.65),
                     s.text(0.7, y, 2.0, 0.65, scene, H2, bold=True, valign="middle"),
                     s.text(2.9, y, 3.1, 0.65, jev, BODY, valign="middle"),
                     s.text(6.2, y, 3.2, 0.65, backend, BODY, MUTE, valign="middle")])
    s.footer()
    s.notes(d["notes"])
    for g in rows:
        for j, sid in enumerate(g):
            s.anim(sid, "fly", "onClick" if j == 0 else "withPrevious", direction="left")
    s.finish()


def s12():
    s, d = Slide(12), C.ACTIONS
    s.title(d["title"])
    items = []
    for i, (y, (txt, page, _short)) in enumerate(zip((1.55, 2.65, 3.75), d["items"])):
        b = s.badge(0.5, y + 0.27, 0.45, str(i + 1), H2)
        t = s.text(1.15, y, 5.3, 1.0, txt, BODY, valign="middle")
        z = s.add("zoom", {"target": page, "name": f"zoom-{page}", "x": inch(7.5), "y": inch(y), "width": inch(1.8),
                           "height": inch(1.0125)}, kind="other")
        items.append((b, t, z, page))
    s.footer(d["footer"], (0.5, 4.95, 7.5, 0.25))
    s.notes(d["notes"])
    run_batch(s.cmds, "s12_shapes")  # zoom 的 id 要建好才查得到
    s.cmds = []
    xml = oc("raw", OUT, s.p)
    for b, t, z, page in items:
        real = int(re.search(rf'<p:cNvPr id="(\d+)" name="(?:!!)?zoom-{page}"', xml).group(1))
        s.kind[real] = "other"
        for j, sid in enumerate((b, t, real)):
            s.anim(sid, "float", "onClick" if j == 0 else "withPrevious")
        # zoom 預設縮圖是空白：用 officecli 截目標頁，加成圖片拿到 relId，把 zoom 的 blip 指過去，再拿掉那張圖片
        png = TMP / f"thumb{page}.png"
        for attempt in range(3):
            try:
                oc("view", OUT, "screenshot", "--page", page, "-o", png, "--screenshot-width", 960,
                   "--screenshot-height", 540)
                break
            except RuntimeError:
                if attempt == 2:
                    raise
                time.sleep(5)
        pic = oc("add", OUT, s.p, "--type", "picture", "--prop", f"src={png}", "--prop", f"name=thumb-{page}",
                 "--prop", "x=0in", "--prop", "y=0in", "--prop", "width=1.8in", "--prop", "height=1.0125in")
        pid = re.search(r"picture\[@id=(\d+)\]", pic).group(1)
        rel = json.loads(oc("get", OUT, f"{s.p}/picture[@id={pid}]", "--json"))["data"]
        rel = (rel.get("results") or [rel])[0]["format"]["relId"]
        s.raw(f"//a:blip[ancestor::*[p:nvGraphicFramePr/p:cNvPr/@id='{real}' or p:nvPicPr/p:cNvPr/@id='{real}']]",
              "setattr", f"r:embed={rel}")
        s.raw(f"//p:pic[p:nvPicPr/p:cNvPr/@id='{pid}']", "remove")
    s.finish()


def fit_text_boxes():
    """view issues 回報的文字溢出：無填色文字框照 officecli 建議值加高。
    靠上對齊的框只改高度、置中的框上下對稱撐開，字在畫面上的位置不變。"""
    issues = json.loads(oc("view", OUT, "issues", "--json"))["data"]["issues"]
    shapes = {r["path"]: r["format"] for r in json.loads(oc("query", OUT, "shape", "--json"))["data"]["results"]}
    cmds = []
    for it in issues:
        m = re.search(r"suggest\.height=([\d.]+)cm", it["message"])
        if not m or it["path"] not in shapes:
            continue
        f = shapes[it["path"]]
        if f.get("fill") not in (None, "none"):
            continue
        cm = lambda v: float(str(v).replace("cm", "")) if str(v).endswith("cm") else float(str(v).replace("pt", "")) / 72 * 2.54
        old, new = cm(f["height"]), float(m.group(1))
        props = {"height": f"{new}cm"}
        if f.get("valign") in ("center", "middle", "ctr", "c", "m"):
            props["y"] = f"{cm(f['y']) - (new - old) / 2:.3f}cm"
        cmds.append({"command": "set", "path": it["path"], "props": props})
    if cmds:
        run_batch(cmds, "fit")
    print(f"fit {len(cmds)} text boxes")


def main():
    t0 = time.time()
    oc("close", OUT, check=False)
    OUT.unlink(missing_ok=True)
    oc("create", OUT)
    oc("set", OUT, "/", "--prop", "slideWidth=10in", "--prop", "slideHeight=5.625in")
    for build in (s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12):
        build()
        print(f"{build.__name__}: {time.time() - t0:.1f}s")
    run_batch(LINKS, "links")
    run_batch([{"command": "raw-set", "part": "/slide[4]", "xpath": "//a:rPr/a:hlinkClick", "action": "remove"}], "links_fix")
    fit_text_boxes()
    oc("close", OUT)
    print(f"built {OUT.name} in {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
