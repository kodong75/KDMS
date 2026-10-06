"""KDMS 설계 문서용 다이어그램 모델과 SVG 그리기.

외부 라이브러리 없이 표준 라이브러리만 쓴다. 같은 모델을 pptx_export.py 가 편집 가능한
PowerPoint 도형으로 옮긴다(좌표·크기·색·글자 크기를 이 파일 한 곳에서 정한다).

스타일 기준(사용자 지정, 2026-10-06): 흰 배경, 플랫·벡터·그림자 없음, 네이비·스카이블루 계열,
강조는 더 진한 색, Pretendard 글꼴, Material Symbols(Outlined) 아이콘, 블록 안 아이콘 위·글 아래,
범례는 왼쪽 아래(실선 = 실시간 질의, 점선 = 비동기 데이터 흐름), 선은 블록에 붙는 연결선.
v0.2: 글·아이콘을 키우고 색을 진하게(가독성 우선).
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
    "navy": "#0A2342",         # 제목·블록 이름
    "primary": "#164B8C",      # 실선·번호·아이콘
    "sky": "#1F74C4",          # 점선(비동기)
    "text": "#16202C",
    "muted": "#3F4E61",        # 보조 글
    "zone_fill": "#F1F6FC",
    "zone_line": "#A9C3E2",
    "core_zone_fill": "#E8F1FB",
    "core_zone_line": "#164B8C",
    "block_fill": "#FFFFFF",
    "block_line": "#6F9BD1",
    "strong_fill": "#D3E5F8",  # 강조 블록
    "strong_line": "#0F3C78",
    "white": "#FFFFFF",
}

# 글자 크기(px, 1600x900 캔버스 기준)와 블록 안 배치
T = {
    "title": 30, "subtitle": 16, "meta": 14,
    "zone_title": 20, "zone_sub": 14.5,
    "block_title": 19, "block_sub": 14.5, "sub_line": 19, "title_line": 24,
    "icon": 40, "icon_gap": 10,
    "label": 14, "legend": 15, "note": 14,
    "pill": 17, "pill_icon": 26, "badge_r": 13, "badge": 15,
    "line": 2.2,
}


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
    strong: bool = False         # 강조(진한 테두리·채움)
    pill: bool = False           # 가로형 알약 모양(사람·외부 주체)

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


class Diagram:
    def __init__(self, width: int, height: int, title: str, subtitle: str, meta: str):
        self.width, self.height = width, height
        self.title, self.subtitle, self.meta = title, subtitle, meta
        self.zones: list[Zone] = []
        self.blocks: dict[str, Block] = {}
        self.links: list[Link] = []
        self.notes: list[str] = []

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
            room = b.w - 20
            for s, size in [(b.title, T["block_title"])] + [(s, T["block_sub"]) for s in b.sub]:
                if not b.pill and text_width(s, size) > room:
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
        if z.sub:
            out.append(f'<text x="{tx}" y="{z.y + 57}" font-size="{T["zone_sub"]}" font-weight="500" fill="{C["muted"]}" text-anchor="{z.align}">{escape(z.sub)}</text>')
        return "\n".join(out)

    def _svg_block(self, b: Block) -> str:
        if b.pill:
            return self._svg_pill(b)
        fill = C["strong_fill"] if b.strong else C["block_fill"]
        line = C["strong_line"] if b.strong else C["block_line"]
        sw = 2.4 if b.strong else 1.6
        lay = self.blocks[b.id].layout()
        cx = lay["cx"]
        out = [f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="12" fill="{fill}" stroke="{line}" stroke-width="{sw}"/>']
        if b.icon:
            out.append(f'<use href="#i-{b.icon}" x="{cx - T["icon"] / 2:.1f}" y="{lay["icon_y"]:.1f}" '
                       f'width="{T["icon"]}" height="{T["icon"]}" fill="{C["primary"]}"/>')
        ty = lay["text_top"]
        out.append(f'<text x="{cx:.1f}" y="{ty + 18:.1f}" font-size="{T["block_title"]}" font-weight="700" fill="{C["navy"]}" text-anchor="middle">{escape(b.title)}</text>')
        for i, s in enumerate(b.sub):
            out.append(f'<text x="{cx:.1f}" y="{ty + T["title_line"] + 15 + i * T["sub_line"]:.1f}" font-size="{T["block_sub"]}" '
                       f'font-weight="500" fill="{C["muted"]}" text-anchor="middle">{escape(s)}</text>')
        if b.num:
            r = T["badge_r"]
            out.append(f'<circle cx="{b.x + 20}" cy="{b.y + 20}" r="{r}" fill="{C["primary"]}"/>')
            out.append(f'<text x="{b.x + 20}" y="{b.y + 25.5}" font-size="{T["badge"]}" font-weight="700" fill="{C["white"]}" text-anchor="middle">{escape(b.num)}</text>')
        return "\n".join(out)

    def pill_layout(self, b: Block) -> tuple[float, float]:
        """알약 블록의 아이콘 x, 글 x."""
        tw = text_width(b.title, T["pill"]) + T["pill_icon"] + 10
        sx = b.x + (b.w - tw) / 2
        return sx, sx + T["pill_icon"] + 10

    def _svg_pill(self, b: Block) -> str:
        ix, tx = self.pill_layout(b)
        s = T["pill_icon"]
        return "\n".join([
            f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="{b.h / 2}" fill="{C["white"]}" stroke="{C["primary"]}" stroke-width="2"/>',
            f'<use href="#i-{b.icon}" x="{ix:.1f}" y="{b.y + (b.h - s) / 2:.1f}" width="{s}" height="{s}" fill="{C["primary"]}"/>',
            f'<text x="{tx:.1f}" y="{b.y + b.h / 2 + 6:.1f}" font-size="{T["pill"]}" font-weight="700" fill="{C["navy"]}">{escape(b.title)}</text>',
        ])

    def legend_box(self) -> tuple[float, float, float, float]:
        return 50, self.height - 72, 440, 52

    def _svg_legend(self) -> str:
        x, y, w, h = self.legend_box()
        my = y + h / 2
        f = T["legend"]
        return "\n".join([
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{C["white"]}" stroke="{C["zone_line"]}" stroke-width="1.4"/>',
            f'<text x="{x + 18}" y="{my + 5}" font-size="{f}" font-weight="800" fill="{C["navy"]}">범례</text>',
            f'<line x1="{x + 70}" y1="{my}" x2="{x + 116}" y2="{my}" stroke="{C["primary"]}" stroke-width="{T["line"]}" marker-end="url(#arrow-sync)"/>',
            f'<text x="{x + 126}" y="{my + 5}" font-size="{f}" font-weight="600" fill="{C["text"]}">실시간 질의</text>',
            f'<line x1="{x + 236}" y1="{my}" x2="{x + 282}" y2="{my}" stroke="{C["sky"]}" stroke-width="{T["line"]}" stroke-dasharray="8 6" marker-end="url(#arrow-async)"/>',
            f'<text x="{x + 292}" y="{my + 5}" font-size="{f}" font-weight="600" fill="{C["text"]}">비동기 데이터 흐름</text>',
        ])

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
                + f'font-family="{FONT_STACK}" role="img" aria-label="{escape(self.title)}">')
        body = [head, style, "<defs>", *defs, "</defs>",
                f'<rect width="{self.width}" height="{self.height}" fill="#FFFFFF"/>',
                f'<rect x="50" y="38" width="7" height="44" rx="3.5" fill="{C["primary"]}"/>',
                f'<text x="70" y="68" font-size="{T["title"]}" font-weight="800" fill="{C["navy"]}">{escape(self.title)}</text>',
                f'<text x="70" y="96" font-size="{T["subtitle"]}" font-weight="500" fill="{C["muted"]}">{escape(self.subtitle)}</text>',
                f'<text x="{self.width - 50}" y="68" font-size="{T["meta"]}" font-weight="500" fill="{C["muted"]}" text-anchor="end">{escape(self.meta)}</text>']
        body += [self._svg_zone(z) for z in self.zones]
        body += [self._svg_link(l) for l in self.links]
        body += [self._svg_block(b) for b in self.blocks.values()]
        body.append(self._svg_legend())
        for i, (nx, ny, n) in enumerate(self.note_positions()):
            body.append(f'<text x="{nx}" y="{ny}" font-size="{T["note"]}" font-weight="500" fill="{C["muted"]}" text-anchor="end">{escape(n)}</text>')
        body.append("</svg>")
        return "\n".join(x for x in body if x)

    def note_positions(self) -> list[tuple[float, float, str]]:
        return [(self.width - 50, self.height - 46 + i * 22, n) for i, n in enumerate(self.notes)]
