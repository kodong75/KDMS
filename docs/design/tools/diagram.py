"""KDMS 설계 문서용 다이어그램 그리기(SVG).

외부 라이브러리 없이 표준 라이브러리만 쓴다. 블록은 모서리가 둥근 직사각형, 블록 안에는
아이콘(위)과 글(아래)을 둔다. 선은 블록의 변(上下左右) 중앙을 잇는 연결선으로 그려서,
블록 좌표만 바꾸면 선이 따라온다.

스타일 기준(사용자 지정, 2026-10-06): 흰 배경, 플랫·벡터·그림자 없음, 네이비·스카이블루 계열,
강조는 더 진한 색, Pretendard 글꼴, Material Symbols(Outlined) 아이콘,
범례는 왼쪽 아래(실선 = 실시간 질의, 점선 = 비동기 데이터 흐름).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from html import escape
from pathlib import Path

DESIGN_DIR = Path(__file__).resolve().parent.parent
ICON_DIR = DESIGN_DIR / "assets" / "icons"

FONT_STACK = "'Pretendard Variable', Pretendard, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"

# 색상 토큰. 문서 HTML 의 CSS 변수(assets/doc.css)와 같은 값을 쓴다.
C = {
    "navy": "#0B2545",        # 제목·강조 글
    "primary": "#1B4F8F",     # 실선·번호·강조 테두리
    "sky": "#3B8FD9",         # 점선(비동기)
    "icon": "#1F5FA8",
    "text": "#1E2A3A",
    "muted": "#5B6B80",
    "zone_fill": "#F4F8FD",
    "zone_line": "#C9DCF0",
    "core_zone_fill": "#EDF4FC",
    "block_fill": "#FFFFFF",
    "block_line": "#A9C6E8",
    "strong_fill": "#DCEAF9",  # 강조 블록 채움
    "strong_line": "#1B4F8F",
    "white": "#FFFFFF",
}


def text_width(s: str, size: float) -> float:
    """글 폭 추정(한글 1em, 그 밖 0.56em). 라벨 배경 크기 계산용."""
    w = 0.0
    for ch in s:
        w += size * (1.0 if ord(ch) > 0x2E80 else 0.56)
    return w


def load_icon(name: str) -> str:
    """Material Symbols SVG 파일에서 path d 값만 꺼낸다(viewBox 0 -960 960 960)."""
    src = (ICON_DIR / f"{name}.svg").read_text(encoding="utf-8")
    paths = re.findall(r'<path d="([^"]+)"', src)
    if not paths:
        raise ValueError(f"아이콘 path 없음: {name}")
    return "".join(f'<path d="{d}"/>' for d in paths)


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

    def anchor(self, side: str, offset: float = 0.0) -> tuple[float, float]:
        """변 중앙(또는 중앙에서 offset 만큼 옮긴 점)."""
        cx, cy = self.x + self.w / 2, self.y + self.h / 2
        if side == "l":
            return self.x, cy + offset
        if side == "r":
            return self.x + self.w, cy + offset
        if side == "t":
            return cx + offset, self.y
        if side == "b":
            return cx + offset, self.y + self.h
        raise ValueError(side)


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
    label_at: float = 0.5     # 선 위 라벨 위치(0~1, 가장 긴 구간 기준)


class Diagram:
    def __init__(self, width: int, height: int, title: str, subtitle: str, meta: str):
        self.width, self.height = width, height
        self.title, self.subtitle, self.meta = title, subtitle, meta
        self.zones: list[Zone] = []
        self.blocks: dict[str, Block] = {}
        self.links: list[Link] = []
        self.pills: list[tuple[str, float, float, float, float, str, str]] = []
        self.notes: list[str] = []

    # ---- 구성 ----
    def zone(self, *a, **k) -> Zone:
        z = Zone(*a, **k)
        self.zones.append(z)
        return z

    def block(self, *a, **k) -> Block:
        b = Block(*a, **k)
        self.blocks[b.id] = b
        return b

    def pill(self, id: str, x: float, y: float, w: float, h: float, text: str, icon: str):
        """가로형 작은 블록(사람·외부 주체). 연결선 기준점은 Block 으로도 등록한다."""
        self.pills.append((id, x, y, w, h, text, icon))
        self.blocks[id] = Block(id, x, y, w, h, text)

    def link(self, *a, **k) -> Link:
        l = Link(*a, **k)
        self.links.append(l)
        return l

    # ---- 그리기 ----
    def _icons_used(self) -> list[str]:
        names = {b.icon for b in self.blocks.values() if b.icon}
        names |= {p[6] for p in self.pills}
        return sorted(names)

    def _route(self, l: Link) -> list[tuple[float, float]]:
        a, b = self.blocks[l.a], self.blocks[l.b]
        p1, p2 = a.anchor(l.a_side, l.a_off), b.anchor(l.b_side, l.b_off)
        horiz = l.a_side in "lr" and l.b_side in "lr"
        vert = l.a_side in "tb" and l.b_side in "tb"
        if abs(p1[0] - p2[0]) < 0.5 or abs(p1[1] - p2[1]) < 0.5:
            return [p1, p2]
        if horiz:
            mx = (p1[0] + p2[0]) / 2
            return [p1, (mx, p1[1]), (mx, p2[1]), p2]
        if vert:
            my = (p1[1] + p2[1]) / 2
            return [p1, (p1[0], my), (p2[0], my), p2]
        if l.a_side in "tb":
            return [p1, (p1[0], p2[1]), p2]
        return [p1, (p2[0], p1[1]), p2]

    def _svg_link(self, l: Link) -> str:
        pts = self._route(l)
        d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        color = C["sky"] if l.dashed else C["primary"]
        dash = ' stroke-dasharray="7 5"' if l.dashed else ""
        marker = "arrow-async" if l.dashed else "arrow-sync"
        out = [f'<path d="{d}" fill="none" stroke="{color}" stroke-width="1.8"{dash} '
               f'stroke-linejoin="round" marker-end="url(#{marker})"/>']
        if l.label:
            # 가장 긴 구간 위에 라벨을 둔다
            segs = list(zip(pts, pts[1:]))
            (x1, y1), (x2, y2) = max(segs, key=lambda s: abs(s[0][0] - s[1][0]) + abs(s[0][1] - s[1][1]))
            lx, ly = x1 + (x2 - x1) * l.label_at, y1 + (y2 - y1) * l.label_at
            size = 12
            tw = text_width(l.label, size) + 10
            if abs(y1 - y2) < 0.5:   # 가로 구간: 선 위쪽
                rx, ry = lx - tw / 2, ly - 22
                tx, ty = lx, ly - 10
            else:                     # 세로 구간: 선 오른쪽
                rx, ry = lx + 6, ly - 10
                tx, ty = lx + 6 + tw / 2, ly + 4
            out.append(f'<rect x="{rx:.1f}" y="{ry:.1f}" width="{tw:.1f}" height="18" rx="4" fill="{C["white"]}"/>')
            out.append(f'<text x="{tx:.1f}" y="{ty:.1f}" font-size="{size}" font-weight="500" '
                       f'fill="{color if l.dashed else C["primary"]}" text-anchor="middle">{escape(l.label)}</text>')
        return "\n".join(out)

    def _svg_zone(self, z: Zone) -> str:
        fill = C["core_zone_fill"] if z.core else C["zone_fill"]
        line = C["primary"] if z.core else C["zone_line"]
        sw = 1.6 if z.core else 1.2
        tx = z.x + 24 if z.align == "start" else z.x + z.w - 24
        out = [f'<rect x="{z.x}" y="{z.y}" width="{z.w}" height="{z.h}" rx="18" fill="{fill}" stroke="{line}" stroke-width="{sw}"/>',
               f'<text x="{tx}" y="{z.y + 32}" font-size="17" font-weight="700" fill="{C["navy"]}" text-anchor="{z.align}">{escape(z.title)}</text>']
        if z.sub:
            out.append(f'<text x="{tx}" y="{z.y + 52}" font-size="12.5" fill="{C["muted"]}" text-anchor="{z.align}">{escape(z.sub)}</text>')
        return "\n".join(out)

    def _svg_block(self, b: Block) -> str:
        fill = C["strong_fill"] if b.strong else C["block_fill"]
        line = C["strong_line"] if b.strong else C["block_line"]
        sw = 2 if b.strong else 1.3
        out = [f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="12" fill="{fill}" stroke="{line}" stroke-width="{sw}"/>']
        icon, gap, title_h, sub_h = 30, 12, 18, 17
        content = (icon + gap if b.icon else 0) + title_h + sub_h * len(b.sub)
        top = b.y + (b.h - content) / 2
        cx = b.x + b.w / 2
        if b.icon:
            out.append(f'<use href="#i-{b.icon}" x="{cx - icon / 2:.1f}" y="{top:.1f}" width="{icon}" height="{icon}" fill="{C["primary"] if b.strong else C["icon"]}"/>')
            top += icon + gap
        out.append(f'<text x="{cx:.1f}" y="{top + 14:.1f}" font-size="16" font-weight="700" fill="{C["navy"]}" text-anchor="middle">{escape(b.title)}</text>')
        for i, s in enumerate(b.sub):
            out.append(f'<text x="{cx:.1f}" y="{top + title_h + 14 + i * sub_h:.1f}" font-size="12.5" fill="{C["muted"]}" text-anchor="middle">{escape(s)}</text>')
        if b.num:
            out.append(f'<circle cx="{b.x + 18}" cy="{b.y + 18}" r="11" fill="{C["primary"]}"/>')
            out.append(f'<text x="{b.x + 18}" y="{b.y + 22.5}" font-size="12.5" font-weight="700" fill="{C["white"]}" text-anchor="middle">{escape(b.num)}</text>')
        return "\n".join(out)

    def _svg_pill(self, p) -> str:
        _, x, y, w, h, text, icon = p
        tw = text_width(text, 15) + 30
        sx = x + (w - tw) / 2
        return "\n".join([
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h / 2}" fill="{C["white"]}" stroke="{C["primary"]}" stroke-width="1.6"/>',
            f'<use href="#i-{icon}" x="{sx:.1f}" y="{y + (h - 22) / 2:.1f}" width="22" height="22" fill="{C["primary"]}"/>',
            f'<text x="{sx + 30:.1f}" y="{y + h / 2 + 5:.1f}" font-size="15" font-weight="600" fill="{C["navy"]}">{escape(text)}</text>',
        ])

    def _svg_legend(self) -> str:
        x, y = 50, self.height - 82
        w, h = 380, 56
        return "\n".join([
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{C["white"]}" stroke="{C["zone_line"]}" stroke-width="1.2"/>',
            f'<text x="{x + 18}" y="{y + 33}" font-size="13" font-weight="700" fill="{C["navy"]}">범례</text>',
            f'<line x1="{x + 64}" y1="{y + 28}" x2="{x + 104}" y2="{y + 28}" stroke="{C["primary"]}" stroke-width="1.8" marker-end="url(#arrow-sync)"/>',
            f'<text x="{x + 114}" y="{y + 33}" font-size="13" fill="{C["text"]}">실시간 질의</text>',
            f'<line x1="{x + 210}" y1="{y + 28}" x2="{x + 250}" y2="{y + 28}" stroke="{C["sky"]}" stroke-width="1.8" stroke-dasharray="7 5" marker-end="url(#arrow-async)"/>',
            f'<text x="{x + 260}" y="{y + 33}" font-size="13" fill="{C["text"]}">비동기 데이터 흐름</text>',
        ])

    def svg(self, font_url: str | None = None, standalone: bool = True) -> str:
        defs = [
            '<marker id="arrow-sync" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
            f'<path d="M0,0 L10,5 L0,10 z" fill="{C["primary"]}"/></marker>',
            '<marker id="arrow-async" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
            f'<path d="M0,0 L10,5 L0,10 z" fill="{C["sky"]}"/></marker>',
        ]
        for name in self._icons_used():
            defs.append(f'<symbol id="i-{name}" viewBox="0 -960 960 960">{load_icon(name)}</symbol>')
        style = ""
        if font_url:
            style = (f"<style>@font-face{{font-family:'Pretendard Variable';font-weight:45 920;"
                     f"src:url('{font_url}') format('woff2-variations');}}</style>")
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" '
                + (f'width="{self.width}" height="{self.height}" ' if standalone else "")
                + f'font-family="{FONT_STACK}" role="img" aria-label="{escape(self.title)}">')
        body = [head, style, "<defs>", *defs, "</defs>",
                f'<rect width="{self.width}" height="{self.height}" fill="#FFFFFF"/>',
                # 머리글: 제목·부제·문서 정보
                f'<rect x="50" y="36" width="6" height="40" rx="3" fill="{C["primary"]}"/>',
                f'<text x="68" y="64" font-size="28" font-weight="800" fill="{C["navy"]}">{escape(self.title)}</text>',
                f'<text x="68" y="90" font-size="14" fill="{C["muted"]}">{escape(self.subtitle)}</text>',
                f'<text x="{self.width - 50}" y="64" font-size="12.5" fill="{C["muted"]}" text-anchor="end">{escape(self.meta)}</text>']
        body += [self._svg_zone(z) for z in self.zones]
        body += [self._svg_link(l) for l in self.links]
        body += [self._svg_block(b) for b in self.blocks.values() if b.id not in {p[0] for p in self.pills}]
        body += [self._svg_pill(p) for p in self.pills]
        body.append(self._svg_legend())
        for i, n in enumerate(self.notes):
            body.append(f'<text x="{self.width - 50}" y="{self.height - 52 + i * 20}" font-size="12.5" fill="{C["muted"]}" text-anchor="end">{escape(n)}</text>')
        body.append("</svg>")
        return "\n".join(x for x in body if x)
