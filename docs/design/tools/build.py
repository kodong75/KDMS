"""문서 원본 만들기: 다이어그램 SVG, 문서 HTML, 문서 목록(index.html).

    python3 docs/design/tools/build.py

표준 라이브러리만 쓴다. PDF·PPTX·PNG 는 export.py 가 이 결과에서 만든다.
"""

from __future__ import annotations

import importlib
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diagram import DESIGN_DIR  # noqa: E402

# 문서 목록. status: done(확정) | draft(초안, 검토 중) | todo(예정)
DOCS = [
    {"no": "D01", "module": "d01_system_architecture", "title": "시스템 구성도", "status": "draft",
     "desc": "원천·kdms.jar·대상 구성 요소와 연결(실시간 질의·비동기 흐름)"},
    {"no": "D02", "title": "SW 아키텍처", "status": "todo",
     "desc": "패키지·계층 구조, 모듈 간 의존 방향, 외부 라이브러리 위치"},
    {"no": "D03", "title": "이관 흐름도", "status": "todo",
     "desc": "워터마크 → 전체 적재 → 변경분 반영 → 쓰기 중지 → 검증 → 전환, 실제 다운타임 구간"},
    {"no": "D04", "title": "데이터 흐름도", "status": "todo",
     "desc": "적재 경로와 CDC 경로의 값 변환, change_log·워터마크·오프셋 기록 시점"},
    {"no": "D05", "title": "관리 테이블 ERD", "status": "todo",
     "desc": "ERD(Entity Relationship Diagram, 개체 관계도). kdms 스키마 테이블·키·상태 값"},
    {"no": "D06", "title": "개발 일정", "status": "todo",
     "desc": "단계별 일정(간트), 완료 기준, 현재 위치"},
    {"no": "D07", "title": "변환 규칙 매핑표", "status": "todo",
     "desc": "MS-SQL → PG 자료형·콜레이션·끝 공백·날짜 규칙과 근거"},
    {"no": "D08", "title": "검증 계획서", "status": "todo",
     "desc": "건수·합계·해시 정규화 규칙, 시험 항목(T-L·T-C·T-N), 합격 기준"},
    {"no": "D09", "title": "전환·롤백 절차서", "status": "todo",
     "desc": "컷오버 단계별 확인 항목, 중단 조건, 되돌리기 방법"},
    {"no": "D10", "title": "보안·권한 설계", "status": "todo",
     "desc": "원천·대상 최소 권한, 비밀번호 보관, 로그 개인정보 원칙, 폐쇄망 점검"},
    {"no": "D11", "title": "운영 매뉴얼", "status": "todo",
     "desc": "설치·실행·모니터링·장애 대응(로그 사용률, CDC 보존 기간 초과 등)"},
]

STATUS_LABEL = {"done": "확정", "draft": "초안", "todo": "예정"}


def page(title: str, kicker: str, meta_html: str, body: str, css: str) -> str:
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{escape(title)}</title>
<link rel="stylesheet" href="{css}">
</head>
<body>
<main class="page">
<header class="doc-head">
  <div><div class="kicker">{escape(kicker)}</div><h1>{escape(title)}</h1></div>
  <div class="meta">{meta_html}</div>
</header>
{body}
</main>
</body>
</html>
"""


def build_doc(doc: dict) -> None:
    mod = importlib.import_module(doc["module"])
    d = mod.build()
    # 단독 SVG: 브라우저로 바로 열 때 글꼴을 상대 경로로 읽는다
    (DESIGN_DIR / "diagrams").mkdir(exist_ok=True)
    (DESIGN_DIR / "diagrams" / f"{mod.ID}.svg").write_text(
        d.svg(font_url="../assets/fonts/PretendardVariable.woff2"), encoding="utf-8")
    # 문서 HTML: SVG 를 본문에 넣어 페이지 글꼴(doc.css)을 그대로 쓴다
    meta = (f"KDMS-{doc['no']} · {mod.VERSION} · {mod.DATE}<br>"
            f'<a class="no-print" href="index.html">문서 목록</a> · '
            f'<a class="no-print" href="dist/{mod.ID}.pdf">PDF</a> · '
            f'<a class="no-print" href="dist/{mod.ID}.pptx">PPTX</a>')
    body = f'<figure class="diagram">{d.svg(standalone=False)}</figure>\n{mod.EXPLAIN}'
    (DESIGN_DIR / f"{mod.ID}.html").write_text(
        page(f"KDMS {doc['title']}", f"KDMS 설계 문서 {doc['no']}", meta, body, "assets/doc.css"),
        encoding="utf-8")
    doc["id"] = mod.ID
    doc["version"] = mod.VERSION
    doc["date"] = mod.DATE


def build_index() -> None:
    rows = []
    for doc in DOCS:
        if "id" in doc:
            links = (f'<span class="links"><a href="{doc["id"]}.html">보기</a>'
                     f'<a href="dist/{doc["id"]}.pdf">PDF</a>'
                     f'<a href="dist/{doc["id"]}.pptx">PPTX</a></span>')
            ver = f'{doc["version"]} · {doc["date"]}'
        else:
            links, ver = "—", "—"
        rows.append(
            f'<tr><td>{doc["no"]}</td><td><strong>{escape(doc["title"])}</strong><br>'
            f'<span style="color:var(--muted)">{escape(doc["desc"])}</span></td>'
            f'<td><span class="status {doc["status"]}">{STATUS_LABEL[doc["status"]]}</span></td>'
            f'<td>{ver}</td><td>{links}</td></tr>')
    body = ("<p>원본은 HTML+SVG, 배포본은 같은 원본에서 만든 PDF(문서)와 PPTX(발표용 한 장)입니다. "
            "만드는 방법은 <code>docs/design/README.md</code>.</p>\n"
            "<table><thead><tr><th>번호</th><th>문서</th><th>상태</th><th>버전</th><th>파일</th></tr></thead>\n<tbody>\n"
            + "\n".join(rows) + "\n</tbody></table>")
    (DESIGN_DIR / "index.html").write_text(
        page("KDMS 설계 문서 목록", "KDMS 설계 문서", "MS-SQL 2019 → PostgreSQL 16", body, "assets/doc.css"),
        encoding="utf-8")


def main() -> None:
    for doc in DOCS:
        if doc.get("module"):
            build_doc(doc)
    build_index()
    print("built:", ", ".join(d["id"] for d in DOCS if "id" in d), "+ index.html")


if __name__ == "__main__":
    main()
