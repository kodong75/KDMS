"""SVG path(d) → 다각형 점 목록. PowerPoint 자유형 도형으로 아이콘을 옮길 때 쓴다.

M L H V C S Q T A Z(대·소문자) 지원. 곡선·호는 짧은 직선으로 나눈다.
"""

from __future__ import annotations

import math
import re

_TOKEN = re.compile(r"[MLHVCSQTAZmlhvcsqtaz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
_ARGS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
STEPS = 12  # 곡선 하나를 나누는 개수


def _flags(tokens: list[str], i: int) -> tuple[list[float], int]:
    """호(A) 인자. 플래그 두 개가 '0 0' 없이 '00' 처럼 붙어 올 수 있다."""
    out: list[float] = []
    while len(out) < 7:
        t = tokens[i]
        if len(out) in (3, 4) and len(t) > 1 and t[0] in "01" and not t.startswith(("0.", "1.")):
            out.append(float(t[0]))
            tokens[i] = t[1:]
            continue
        out.append(float(t))
        i += 1
    return out, i


def _arc(x1, y1, rx, ry, phi, fa, fs, x2, y2) -> list[tuple[float, float]]:
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    phi = math.radians(phi)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if fa == fs:
        co = -co
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    n = max(4, int(abs(dt) / (math.pi / 12)))
    pts = []
    for k in range(1, n + 1):
        t = t1 + dt * k / n
        pts.append((cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp,
                    cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp))
    return pts


def flatten(d: str) -> list[list[tuple[float, float]]]:
    tokens = _TOKEN.findall(d)
    polys: list[list[tuple[float, float]]] = []
    cur: list[tuple[float, float]] = []
    x = y = sx = sy = 0.0
    cmd = ""
    last_c = last_q = None  # 직전 제어점(S·T 반사용)
    i = 0
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if cur:
                    polys.append(cur)
                cur, x, y = [], sx, sy
                last_c = last_q = None
                continue
        up, rel = cmd.upper(), cmd.islower()
        if up == "A":
            a, i = _flags(tokens, i)
        else:
            a = [float(t) for t in tokens[i:i + _ARGS[up]]]
            i += _ARGS[up]
        ox, oy = (x, y) if rel else (0.0, 0.0)
        nc = nq = None
        if up == "M":
            if cur:
                polys.append(cur)
            x, y = a[0] + ox, a[1] + oy
            sx, sy = x, y
            cur = [(x, y)]
            cmd = "l" if rel else "L"   # 이어지는 좌표는 선
        elif up == "L":
            x, y = a[0] + ox, a[1] + oy
            cur.append((x, y))
        elif up == "H":
            x = a[0] + (ox if rel else 0)
            cur.append((x, y))
        elif up == "V":
            y = a[0] + (oy if rel else 0)
            cur.append((x, y))
        elif up in "CS":
            if up == "C":
                c1 = (a[0] + ox, a[1] + oy)
                c2, e = (a[2] + ox, a[3] + oy), (a[4] + ox, a[5] + oy)
            else:
                c1 = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
                c2, e = (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy)
            p0 = (x, y)
            for k in range(1, STEPS + 1):
                t = k / STEPS
                mt = 1 - t
                cur.append((mt ** 3 * p0[0] + 3 * mt * mt * t * c1[0] + 3 * mt * t * t * c2[0] + t ** 3 * e[0],
                            mt ** 3 * p0[1] + 3 * mt * mt * t * c1[1] + 3 * mt * t * t * c2[1] + t ** 3 * e[1]))
            x, y = e
            nc = c2
        elif up in "QT":
            if up == "Q":
                c, e = (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy)
            else:
                c = (2 * x - last_q[0], 2 * y - last_q[1]) if last_q else (x, y)
                e = (a[0] + ox, a[1] + oy)
            p0 = (x, y)
            for k in range(1, STEPS + 1):
                t = k / STEPS
                mt = 1 - t
                cur.append((mt * mt * p0[0] + 2 * mt * t * c[0] + t * t * e[0],
                            mt * mt * p0[1] + 2 * mt * t * c[1] + t * t * e[1]))
            x, y = e
            nq = c
        elif up == "A":
            ex, ey = a[5] + ox, a[6] + oy
            cur.extend(_arc(x, y, a[0], a[1], a[2], a[3], a[4], ex, ey))
            x, y = ex, ey
        last_c, last_q = nc, nq
    if cur:
        polys.append(cur)
    return [p for p in polys if len(p) >= 3]
