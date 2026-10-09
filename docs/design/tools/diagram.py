"""KDMS 설계 문서용 다이어그램 모델과 SVG 그리기.

외부 라이브러리 없이 표준 라이브러리만 쓴다. 같은 모델을 pptx_export.py 가 편집 가능한
PowerPoint 도형으로 옮긴다(좌표·크기·색·글자 크기를 이 파일 한 곳에서 정한다).

스타일 기준: 사용자 다이어그램 규칙(/diagram-style, 2026-10-09 갱신)과 docs/design/README.md.
색은 이 프로젝트만 녹색 계열(사용자 2026-10-09). 규칙의 네이비·스카이블루 자리에 같은 역할로 넣었다.
흰 배경, 플랫·그림자 없음, 고정 색 코드(C), 최소 글자 17px(T), Pretendard(calt 끔), Material Symbols,
블록 안 아이콘 위·글 아래, 강조(진한 네이비 채움 + 흰 글자)는 주 경로에만,
범례 왼쪽 아래(구성도: 실선 = 실시간 질의·점선 = 비동기 흐름, 흐름도: 실선 = 처리 순서·점선 = 불합격 경로).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from html import escape
from pathlib import Path

DESIGN_DIR = Path(__file__).resolve().parent.parent
ICON_DIR = DESIGN_DIR / "assets" / "icons"

FONT_STACK = "'Pretendard Variable', Pretendard, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
FONT_PPTX = "Pretendard"

# 색상 토큰. assets/doc.css 의 CSS 변수와 같은 값을 쓴다.
C = {
    "navy": "#0B3D2E",         # 진한 녹색: 제목·강조 블록 채움
    "primary": "#1E6B4F",      # 실선·번호·아이콘
    "sky": "#2E9E6E",          # 밝은 녹색: 점선(비동기·불합격 경로)
    "text": "#16202C",
    "muted": "#3F4E61",        # 보조 글
    "zone_fill": "#EEF8F3",    # 영역 바탕
    "zone_line": "#9DD3B8",
    "core_zone_fill": "#EEF8F3",
    "core_zone_line": "#1E6B4F",
    "block_fill": "#FFFFFF",
    "block_line": "#9DD3B8",   # 블록 테두리
    "strong_fill": "#0B3D2E",  # 강조(주 경로): 진한 녹색 채움 + 흰 글자
    "strong_line": "#0B3D2E",
    "tint_fill": "#D5EFE2",    # 표시(주의 등): 연한 채움 + 진한 테두리. 강조와 따로 쓴다
    "tint_line": "#1E6B4F",
    "white": "#FFFFFF",
}

# 글자 크기(px, 1600x900 캔버스 기준)와 블록 안 배치
T = {
    "title": 36, "subtitle": 18, "meta": 17,
    "zone_title": 20, "zone_sub": 17, "zone_sub_line": 22,
    "block_title": 20, "block_sub": 17, "sub_line": 22, "title_line": 26,
    "icon": 40, "icon_gap": 10,
    "label": 17, "legend": 17, "note": 17,
    "pill": 18, "pill_icon": 26, "badge_r": 14, "badge": 17,
    "line": 2.2,
}
# 최소 글자 17px: 1600x900 을 와이드 슬라이드(960x540pt)에 채우면 0.6배 → 10pt 이상
MIN_FONT = 17


def text_width(s: str, size: float) -> float:
    """글 폭 추정(한글 1em, 그 밖 0.55em). 라벨 배경·넘침 검사용."""
    return sum(size * (1.0 if ord(ch) > 0x2E80 else 0.55) for ch in s)


def icon_paths(name: str) -> list[str]:
    """Material Symbols SVG 파일의 path d 값(viewBox 0 -960 960 960)."""
    src = (ICON_DIR / f"{name}.svg").read_text(encoding="utf-8")
    paths = re.findall(r'<path d="([^"]+)"', src)
    if not paths:
        raise ValueError(f"아이콘 path 없음: {name}")
    return paths


@dataclass
class Block:
    id: str
    x: float
    y: float
    w: float
    h: float
    title: str
    sub: list[str] = field(default_factory=list)
    icon: str | None = None
    num: str | None = None       # 왼쪽 위 번호 배지
    strong: bool = False         # 강조(주 경로): 진한 네이비 채움 + 흰 글자
    pill: bool = False           # 가로형 알약 모양(사람·외부 주체)
    tint: bool = False           # 표시(주의 등): 연한 채움 + 진한 테두리
    shape: str = "rect"          # rect | diamond(판단) | start(시작·끝, 진한 알약) | point(연결점 원)

    def style(self) -> dict:
        """채움·테두리·글·아이콘 색. SVG 와 PPTX 가 같이 쓴다."""
        if self.strong or self.shape == "start":
            return {"fill": C["strong_fill"], "line": C["strong_line"], "lw": 2.4, "title": C["white"],
                    "sub": C["white"], "icon": C["white"], "badge": C["white"], "badge_text": C["navy"]}
        if self.tint:
            return {"fill": C["tint_fill"], "line": C["tint_line"], "lw": 2.2, "title": C["navy"],
                    "sub": C["text"], "icon": C["primary"], "badge": C["primary"], "badge_text": C["white"]}
        if self.shape == "point":
            return {"fill": C["white"], "line": C["primary"], "lw": 2.2, "title": C["primary"],
                    "sub": C["muted"], "icon": C["primary"], "badge": C["primary"], "badge_text": C["white"]}
        return {"fill": C["block_fill"], "line": C["block_line"], "lw": 1.8, "title": C["navy"],
                "sub": C["muted"], "icon": C["primary"], "badge": C["primary"], "badge_text": C["white"]}

    def anchor(self, side: str, offset: float = 0.0) -> tuple[float, float]:
        cx, cy = self.x + self.w / 2, self.y + self.h / 2
        return {"l": (self.x, cy + offset), "r": (self.x + self.w, cy + offset),
                "t": (cx + offset, self.y), "b": (cx + offset, self.y + self.h)}[side]

    def layout(self) -> dict:
        """블록 안 아이콘·글 위치. SVG 와 PPTX 가 같이 쓴다."""
        content = (T["icon"] + T["icon_gap"] if self.icon else 0) + T["title_line"] + T["sub_line"] * len(self.sub)
        top = self.y + (self.h - content) / 2
        icon_y = top
        text_top = top + (T["icon"] + T["icon_gap"] if self.icon else 0)
        return {"cx": self.x + self.w / 2, "icon_y": icon_y, "text_top": text_top}


@dataclass
class Zone:
    x: float
    y: float
    w: float
    h: float
    title: str
    sub: str = ""
    core: bool = False
    align: str = "start"   # 제목 정렬: start | end


@dataclass
class Link:
    a: str
    a_side: str
    b: str
    b_side: str
    dashed: bool = False
    label: str = ""
    a_off: float = 0.0
    b_off: float = 0.0
    label_at: float = 0.5


@dataclass
class Text:
    """자유 글. y 는 글 기준선. anchor: start | middle | end."""
    x: float
    y: float
    text: str
    size: float = 14
    bold: bool = False
    color: str = "#3F4E61"
    anchor: str = "start"


@dataclass
class Line:
    """블록에 붙지 않는 선(격자·의존 방향 등)."""
    x1: float
    y1: float
    x2: float
    y2: float
    color: str = "#164B8C"
    width: float = 2.2
    dashed: bool = False
    arrow: bool = False


@dataclass
class Bar:
    """채운 사각형(간트 막대·구간 배경). 글은 가운데."""
    x: float
    y: float
    w: float
    h: float
    fill: str
    line: str | None = None
    lw: float = 1.4
    rx: float = 6
    text: str = ""
    text_color: str = "#FFFFFF"
    size: float = 14
    name: str = "막대"


class Diagram:
    def __init__(self, width: int, height: int, title: str, subtitle: str, meta: str):
        self.width, self.height = width, height
        self.title, self.subtitle, self.meta = title, subtitle, meta
        self.zones: list[Zone] = []
        self.blocks: dict[str, Block] = {}
        self.links: list[Link] = []
        self.notes: list[str] = []
        self.texts: list[Text] = []
        self.lines: list[Line] = []
        self.bars: list[Bar] = []
        # 범례 항목: ("sync"|"async"|"#색", 글). 기본은 사용자 지정 두 가지
        self.legend: list[tuple[str, str]] = [("sync", "실시간 질의"), ("async", "비동기 데이터 흐름")]

    def zone(self, *a, **k) -> Zone:
        z = Zone(*a, **k)
        self.zones.append(z)
        return z

    def block(self, *a, **k) -> Block:
        b = Block(*a, **k)
        self.blocks[b.id] = b
        return b

    def link(self, *a, **k) -> Link:
        l = Link(*a, **k)
        self.links.append(l)
        return l

    def text(self, *a, **k) -> Text:
        t = Text(*a, **k)
        self.texts.append(t)
        return t

    def line(self, *a, **k) -> Line:
        l = Line(*a, **k)
        self.lines.append(l)
        return l

    def bar(self, *a, **k) -> Bar:
        b = Bar(*a, **k)
        self.bars.append(b)
        return b

    # ---- 공통 계산 ----
    def route(self, l: Link) -> list[tuple[float, float]]:
        a, b = self.blocks[l.a], self.blocks[l.b]
        p1, p2 = a.anchor(l.a_side, l.a_off), b.anchor(l.b_side, l.b_off)
        if abs(p1[0] - p2[0]) < 0.5 or abs(p1[1] - p2[1]) < 0.5:
            return [p1, p2]
        if l.a_side in "lr" and l.b_side in "lr":
            mx = (p1[0] + p2[0]) / 2
            return [p1, (mx, p1[1]), (mx, p2[1]), p2]
        if l.a_side in "tb" and l.b_side in "tb":
            my = (p1[1] + p2[1]) / 2
            return [p1, (p1[0], my), (p2[0], my), p2]
        if l.a_side in "tb":
            return [p1, (p1[0], p2[1]), p2]
        return [p1, (p2[0], p1[1]), p2]

    def label_box(self, l: Link) -> tuple[float, float, float, float, float, float]:
        """라벨 배경 사각형(x, y, w, h)과 글 기준점(가운데 x, 글 위 y)."""
        pts = self.route(l)
        segs = list(zip(pts, pts[1:]))
        (x1, y1), (x2, y2) = max(segs, key=lambda s: abs(s[0][0] - s[1][0]) + abs(s[0][1] - s[1][1]))
        lx, ly = x1 + (x2 - x1) * l.label_at, y1 + (y2 - y1) * l.label_at
        w, h = text_width(l.label, T["label"]) + 12, T["label"] + 8
        if abs(y1 - y2) < 0.5:   # 가로 구간: 선 위
            x, y = lx - w / 2, ly - h - 4
        else:                     # 세로 구간: 선 오른쪽
            x, y = lx + 6, ly - h / 2
        return x, y, w, h, x + w / 2, y + 4

    def check(self) -> list[str]:
        """블록 글이 블록 폭을 넘는지 검사."""
        out = []
        for b in self.blocks.values():
            room = b.w - 20 if b.shape != "diamond" else b.w * 0.55
            for s, size in [(b.title, T["block_title"])] + [(s, T["block_sub"]) for s in b.sub]:
                if not b.pill and b.shape not in ("start", "point") and text_width(s, size) > room:
                    out.append(f"{b.id}: '{s}' 폭 {text_width(s, size):.0f} > {room:.0f}")
        return out

    # ---- SVG ----
    def _svg_link(self, l: Link) -> str:
        pts = self.route(l)
        d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        color = C["sky"] if l.dashed else C["primary"]
        dash = ' stroke-dasharray="8 6"' if l.dashed else ""
        marker = "arrow-async" if l.dashed else "arrow-sync"
        out = [f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{T["line"]}"{dash} '
               f'stroke-linejoin="round" marker-end="url(#{marker})"/>']
        if l.label:
            x, y, w, h, tx, ty = self.label_box(l)
            out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="4" fill="{C["white"]}"/>')
            out.append(f'<text x="{tx:.1f}" y="{ty + T["label"] - 1:.1f}" font-size="{T["label"]}" font-weight="600" '
                       f'fill="{color}" text-anchor="middle">{escape(l.label)}</text>')
        return "\n".join(out)

    def _svg_zone(self, z: Zone) -> str:
        fill = C["core_zone_fill"] if z.core else C["zone_fill"]
        line = C["core_zone_line"] if z.core else C["zone_line"]
        sw = 2 if z.core else 1.4
        tx = z.x + 22 if z.align == "start" else z.x + z.w - 22
        out = [f'<rect x="{z.x}" y="{z.y}" width="{z.w}" height="{z.h}" rx="18" fill="{fill}" stroke="{line}" stroke-width="{sw}"/>',
               f'<text x="{tx}" y="{z.y + 34}" font-size="{T["zone_title"]}" font-weight="800" fill="{C["navy"]}" text-anchor="{z.align}">{escape(z.title)}</text>']
        for i, sub in enumerate(z.sub.split("\n") if z.sub else []):
            out.append(f'<text x="{tx}" y="{z.y + 60 + i * T["zone_sub_line"]}" font-size="{T["zone_sub"]}" font-weight="500" fill="{C["muted"]}" text-anchor="{z.align}">{escape(sub)}</text>')
        return "\n".join(out)

    def _svg_block(self, b: Block) -> str:
        if b.pill or b.shape == "start":
            return self._svg_pill(b)
        st = b.style()
        if b.shape == "point":
            r = b.w / 2
            return "\n".join([
                f'<circle cx="{b.x + r}" cy="{b.y + r}" r="{r}" fill="{st["fill"]}" stroke="{st["line"]}" stroke-width="{st["lw"]}"/>',
                f'<text x="{b.x + r}" y="{b.y + r + 7}" font-size="{T["block_title"]}" font-weight="800" fill="{st["title"]}" text-anchor="middle">{escape(b.title)}</text>'])
        lay = b.layout()
        cx = lay["cx"]
        if b.shape == "diamond":
            out = [f'<path d="M{cx},{b.y} L{b.x + b.w},{b.y + b.h / 2} L{cx},{b.y + b.h} L{b.x},{b.y + b.h / 2} Z" '
                   f'fill="{st["fill"]}" stroke="{st["line"]}" stroke-width="{st["lw"]}" stroke-linejoin="round"/>']
        else:
            out = [f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="12" fill="{st["fill"]}" stroke="{st["line"]}" stroke-width="{st["lw"]}"/>']
        if b.icon:
            out.append(f'<use href="#i-{b.icon}" x="{cx - T["icon"] / 2:.1f}" y="{lay["icon_y"]:.1f}" '
                       f'width="{T["icon"]}" height="{T["icon"]}" fill="{st["icon"]}"/>')
        ty = lay["text_top"]
        out.append(f'<text x="{cx:.1f}" y="{ty + 19:.1f}" font-size="{T["block_title"]}" font-weight="700" fill="{st["title"]}" text-anchor="middle">{escape(b.title)}</text>')
        for i, s in enumerate(b.sub):
            out.append(f'<text x="{cx:.1f}" y="{ty + T["title_line"] + 17 + i * T["sub_line"]:.1f}" font-size="{T["block_sub"]}" '
                       f'font-weight="500" fill="{st["sub"]}" text-anchor="middle">{escape(s)}</text>')
        if b.num:
            r = T["badge_r"]
            out.append(f'<circle cx="{b.x + 21}" cy="{b.y + 21}" r="{r}" fill="{st["badge"]}"/>')
            out.append(f'<text x="{b.x + 21}" y="{b.y + 27}" font-size="{T["badge"]}" font-weight="700" fill="{st["badge_text"]}" text-anchor="middle">{escape(b.num)}</text>')
        return "\n".join(out)

    def pill_layout(self, b: Block) -> tuple[float, float]:
        """알약 블록의 아이콘 x, 글 x(아이콘 없으면 둘이 같다)."""
        iw = T["pill_icon"] + 10 if b.icon else 0
        tw = text_width(b.title, T["pill"]) + iw
        sx = b.x + (b.w - tw) / 2
        return sx, sx + iw

    def _svg_pill(self, b: Block) -> str:
        ix, tx = self.pill_layout(b)
        s = T["pill_icon"]
        dark = b.shape == "start"
        fill, color = (C["navy"], C["white"]) if dark else (C["white"], C["navy"])
        out = [f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="{b.h / 2}" fill="{fill}" stroke="{C["navy"] if dark else C["primary"]}" stroke-width="2"/>']
        if b.icon:
            out.append(f'<use href="#i-{b.icon}" x="{ix:.1f}" y="{b.y + (b.h - s) / 2:.1f}" width="{s}" height="{s}" fill="{C["white"] if dark else C["primary"]}"/>')
        out.append(f'<text x="{tx:.1f}" y="{b.y + b.h / 2 + 6.5:.1f}" font-size="{T["pill"]}" font-weight="700" fill="{color}">{escape(b.title)}</text>')
        return "\n".join(out)

    def legend_layout(self) -> tuple[tuple[float, float, float, float], list[tuple[str, str, float, float]]]:
        """범례 상자와 항목별 (종류, 글, 표시 x, 글 x)."""
        x, y, h = 50, self.height - 72, 52
        cur = x + 70
        items = []
        for kind, label in self.legend:
            items.append((kind, label, cur, cur + (56 if kind in ("sync", "async") else 34)))
            cur = items[-1][3] + text_width(label, T["legend"]) + 30
        return (x, y, cur - x - 10, h), items

    def _svg_legend(self) -> str:
        (x, y, w, h), items = self.legend_layout()
        my = y + h / 2
        f = T["legend"]
        out = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{C["white"]}" stroke="{C["zone_line"]}" stroke-width="1.4"/>',
               f'<text x="{x + 18}" y="{my + 6}" font-size="{f}" font-weight="800" fill="{C["navy"]}">범례</text>']
        for kind, label, mx, tx in items:
            if kind == "sync":
                out.append(f'<line x1="{mx}" y1="{my}" x2="{mx + 46}" y2="{my}" stroke="{C["primary"]}" stroke-width="{T["line"]}" marker-end="url(#arrow-sync)"/>')
            elif kind == "async":
                out.append(f'<line x1="{mx}" y1="{my}" x2="{mx + 46}" y2="{my}" stroke="{C["sky"]}" stroke-width="{T["line"]}" stroke-dasharray="8 6" marker-end="url(#arrow-async)"/>')
            else:
                out.append(f'<rect x="{mx}" y="{my - 9}" width="24" height="18" rx="4" fill="{kind}" stroke="{C["block_line"]}" stroke-width="1"/>')
            out.append(f'<text x="{tx}" y="{my + 6}" font-size="{f}" font-weight="600" fill="{C["text"]}">{escape(label)}</text>')
        return "\n".join(out)

    def _svg_line(self, l: Line) -> str:
        dash = ' stroke-dasharray="8 6"' if l.dashed else ""
        mk = ""
        if l.arrow:
            mk = ' marker-end="url(#arrow-async)"' if l.dashed else ' marker-end="url(#arrow-sync)"'
        return (f'<line x1="{l.x1}" y1="{l.y1}" x2="{l.x2}" y2="{l.y2}" stroke="{l.color}" '
                f'stroke-width="{l.width}"{dash}{mk}/>')

    def _svg_bar(self, b: Bar) -> str:
        stroke = f' stroke="{b.line}" stroke-width="{b.lw}"' if b.line else ""
        out = [f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="{b.rx}" fill="{b.fill}"{stroke}/>']
        if b.text:
            out.append(f'<text x="{b.x + b.w / 2}" y="{b.y + b.h / 2 + b.size * 0.36:.1f}" font-size="{b.size}" '
                       f'font-weight="700" fill="{b.text_color}" text-anchor="middle">{escape(b.text)}</text>')
        return "\n".join(out)

    def _svg_text(self, t: Text) -> str:
        return (f'<text x="{t.x}" y="{t.y}" font-size="{t.size}" font-weight="{700 if t.bold else 500}" '
                f'fill="{t.color}" text-anchor="{t.anchor}">{escape(t.text)}</text>')

    def svg(self, font_url: str | None = None, standalone: bool = True) -> str:
        defs = []
        for mid, color in (("arrow-sync", C["primary"]), ("arrow-async", C["sky"])):
            defs.append(f'<marker id="{mid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" '
                        f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{color}"/></marker>')
        for name in sorted({b.icon for b in self.blocks.values() if b.icon}):
            defs.append(f'<symbol id="i-{name}" viewBox="0 -960 960 960">'
                        + "".join(f'<path d="{d}"/>' for d in icon_paths(name)) + "</symbol>")
        style = ""
        if font_url:
            style = (f"<style>@font-face{{font-family:'Pretendard Variable';font-weight:45 920;"
                     f"src:url('{font_url}') format('woff2-variations');}}</style>")
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" '
                + (f'width="{self.width}" height="{self.height}" ' if standalone else "")
                + f'font-family="{FONT_STACK}" style="font-feature-settings:\'calt\' 0" role="img" aria-label="{escape(self.title)}">')
        body = [head, style, "<defs>", *defs, "</defs>",
                f'<rect width="{self.width}" height="{self.height}" fill="#FFFFFF"/>',
                f'<rect x="50" y="36" width="7" height="72" rx="3.5" fill="{C["primary"]}"/>',
                f'<text x="70" y="70" font-size="{T["title"]}" font-weight="800" fill="{C["navy"]}">{escape(self.title)}</text>',
                f'<text x="70" y="102" font-size="{T["subtitle"]}" font-weight="500" fill="{C["muted"]}">{escape(self.subtitle)}</text>',
                f'<text x="{self.width - 50}" y="70" font-size="{T["meta"]}" font-weight="500" fill="{C["muted"]}" text-anchor="end">{escape(self.meta)}</text>']
        body += [self._svg_zone(z) for z in self.zones]
        body += [self._svg_bar(b) for b in self.bars]
        body += [self._svg_line(l) for l in self.lines]
        body += [self._svg_link(l) for l in self.links]
        body += [self._svg_block(b) for b in self.blocks.values()]
        body += [self._svg_text(t) for t in self.texts]
        if self.legend:
            body.append(self._svg_legend())
        for i, (nx, ny, n) in enumerate(self.note_positions()):
            body.append(f'<text x="{nx}" y="{ny}" font-size="{T["note"]}" font-weight="500" fill="{C["muted"]}" text-anchor="end">{escape(n)}</text>')
        body.append("</svg>")
        return "\n".join(x for x in body if x)

    def note_positions(self) -> list[tuple[float, float, str]]:
        return [(self.width - 50, self.height - 48 + i * 25, n) for i, n in enumerate(self.notes)]
