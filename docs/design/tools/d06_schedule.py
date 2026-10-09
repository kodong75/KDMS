"""D06 개발 일정. 근거: docs/plan.md §6(단계·완료 기준·상태), 저장소 PR 머지 시각. 6단계부터 날짜는 안(案)이다."""

from datetime import date, timedelta

from diagram import C, Diagram, text_width

ID = "d06-schedule"
TITLE = "개발 일정"
VERSION = "v0.2"
DATE = "2026-10-09"

TODAY = date(2026, 10, 9)
START = date(2026, 9, 28)          # 첫 주 월요일
WEEKS = 6
X0, X1 = 470, 1550                 # 간트 영역
ROW_Y, ROW_H = 192, 68

DONE, DOING, TODO = "done", "doing", "todo"
FILL = {DONE: C["primary"], DOING: C["sky"], TODO: C["tint_fill"]}
TEXT = {DONE: C["white"], DOING: C["white"], TODO: C["primary"]}

# (단계, 내용, 시작, 끝, 상태, 같이 만들 설계 문서)
ROWS = [
    ("0. 환경 준비", "노트북 DB · CDC 켜기 · 전용 계정", date(2026, 10, 1), date(2026, 10, 6), DONE, ""),
    ("1. 골격", "Maven · CLI · 웹 · 관리 테이블", date(2026, 10, 1), date(2026, 10, 6), DONE, "D01 D02 D03 D06"),
    ("2. 스키마 변환", "kdms plan · 세 단계 DDL", date(2026, 10, 1), date(2026, 10, 6), DONE, "D07"),
    ("3. 전체 적재 + 검증", "COPY · 재시작 · 건수·합계·해시", date(2026, 10, 7), date(2026, 10, 8), DONE, "D05 D08"),
    ("4. CDC 수집·반영", "Debezium · change_log · 반영기", date(2026, 10, 8), date(2026, 10, 8), DONE, "D04"),
    ("5. 전환 + 화면·CLI", "상태 기계 · setval · 화면", date(2026, 10, 8), date(2026, 10, 8), DONE, "D09"),
    ("6. MVP 리허설", "처음부터 2회 · 운영 절차서", date(2026, 10, 9), date(2026, 10, 16), DOING, "D10 D11"),
    ("7. 설치본", "jlink 로 JRE 포함 압축본", date(2026, 10, 19), date(2026, 10, 23), TODO, ""),
    ("8. 확장", "22테이블 · 대용량 · 별도 계획", date(2026, 10, 26), date(2026, 10, 30), TODO, ""),
]


def x_of(d: date) -> float:
    days = (d - START).days
    return X0 + (X1 - X0) * days / (WEEKS * 7)


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 개발 일정",
                "0 ~ 5단계는 실제(PR 머지일) · 6단계부터 안(案) · 오늘 2026-10-09",
                f"KDMS-D06 · {VERSION} · {DATE}")
    d.legend = [(C["primary"], "완료"), (C["sky"], "진행 중"), (C["tint_fill"], "예정")]

    top, bottom = 126, ROW_Y + ROW_H * len(ROWS)
    # 머리줄: 월 · 주 시작일
    d.bar(50, top, 1500, 64, C["primary"], rx=8, name="머리줄")
    d.text(70, top + 40, "단계", size=20, bold=True, color=C["white"])
    end = START + timedelta(weeks=WEEKS)
    for m, (ms, me) in {"9월": (START, date(2026, 9, 30)),
                        "10월": (date(2026, 10, 1), date(2026, 10, 31)),
                        "11월": (date(2026, 11, 1), date(2026, 11, 30))}.items():
        d.text((x_of(ms) + x_of(min(me + timedelta(days=1), end))) / 2, top + 26, m,
               size=18, bold=True, color=C["white"], anchor="middle")
    for w in range(WEEKS):
        wd = START + timedelta(weeks=w)
        d.text(x_of(wd) + (X1 - X0) / WEEKS / 2, top + 52, f"{wd.month}/{wd.day}",
               size=17, color=C["white"], anchor="middle")

    # 줄 배경·주 격자
    for i in range(len(ROWS)):
        if i % 2:
            d.bar(50, ROW_Y + i * ROW_H, 1500, ROW_H, C["zone_fill"], rx=0, name="줄 배경")
    for w in range(WEEKS + 1):
        x = x_of(START + timedelta(weeks=w))
        d.bar(x - 0.5, top + 64, 1, bottom - top - 64, "#D7EDE2", rx=0, name="주 격자")
    d.line(50, bottom, 1550, bottom, color=C["zone_line"], width=1.4)

    # 오늘 선(막대 뒤에 깔리도록 먼저 그린다)
    tx = x_of(TODAY) + (X1 - X0) / (WEEKS * 7) / 2
    d.bar(tx - 1, top + 64, 2, bottom - top - 40, C["navy"], rx=0, name="오늘 선")
    d.text(tx + 8, bottom + 24, "오늘 10/9", size=17, bold=True, color=C["navy"])

    # 막대
    for i, (name, desc, s, e, st, docs) in enumerate(ROWS):
        y = ROW_Y + i * ROW_H
        d.text(70, y + 29, name, size=20, bold=True, color=C["navy"])
        d.text(70, y + 54, desc, size=17, color=C["muted"])
        bx, bw = x_of(s), x_of(e + timedelta(days=1)) - x_of(s)
        label = f"{s.month}/{s.day} ~ {e.month}/{e.day}"
        if s == e:
            label = f"{s.month}/{s.day}"
        inside = text_width(label, 17) + 16 <= bw     # 짧은 막대는 날짜를 막대 오른쪽에
        d.bar(bx, y + 16, bw, 36, FILL[st], line=C["primary"] if st == TODO else None, lw=1.2, rx=8,
              text=label if inside else "", text_color=TEXT[st], size=17, name=f"막대 {name}")
        after = bx + bw + 10
        if after < tx + 12 < after + 60:   # 오늘 선에 글이 걸리지 않게
            after = tx + 12
        if not inside:
            d.text(after, y + 40, label, size=17, bold=True, color=C["navy"])
            after += text_width(label, 17) + 14
        if docs:
            if after < tx + 12 < after + text_width(docs, 17) + 12:
                after = tx + 12
            d.text(after, y + 40, docs, size=17, bold=True, color=C["primary"])


    d.notes = [
        "막대 오른쪽 D 번호: 그 단계에 맞춰 만드는 설계 문서 · 6단계부터 날짜는 안(案)",
        "단계마다 브랜치 하나 + draft PR 하나 · 머지는 사용자가 한다(CLAUDE.md §4)",
    ]
    return d


EXPLAIN = """
<h2>1. 단계별 일정</h2>
<table>
<thead><tr><th>단계</th><th>기간</th><th>상태</th><th>완료 근거 (plan §6)</th><th>함께 만들 문서</th></tr></thead>
<tbody>
<tr><td>0. 환경 준비</td><td>10/1 ~ 10/6</td><td>완료</td><td>Mac 에서 1433·5432 접속, <code>KDMS_MOCK</code> CDC 켜짐, <code>kdms_app</code> 로그인</td><td>—</td></tr>
<tr><td>1. 골격</td><td>10/1 ~ 10/6</td><td>완료</td><td>PR #2·#3·#5, Mac 확인 2026-10-06</td><td>D01 구성도, D02 아키텍처, D03 흐름도, D06 일정</td></tr>
<tr><td>2. 스키마 변환</td><td>10/1 ~ 10/6</td><td>완료</td><td>PR #4 머지 2026-10-06</td><td>D07 변환 규칙 매핑표</td></tr>
<tr><td>3. 전체 적재 + 검증</td><td>10/7 ~ 10/8</td><td>완료</td><td>PR #8 머지 2026-10-08</td><td>D05 관리 테이블 ERD, D08 검증 계획서 (아직 없음)</td></tr>
<tr><td>4. CDC 수집·반영</td><td>10/8</td><td>완료</td><td>PR #9 머지 2026-10-08</td><td>D04 데이터 흐름도 (아직 없음)</td></tr>
<tr><td>5. 전환 + 화면·CLI</td><td>10/8</td><td>완료</td><td>PR #10 머지 2026-10-08, 시나리오 S1~S4</td><td>D09 전환·롤백 절차서 (아직 없음)</td></tr>
<tr><td>6. MVP 리허설</td><td>10/9 ~ 10/16 (안)</td><td>진행 중</td><td>처음부터 2회 모두 합격, 절차서만 보고 다시 할 수 있음. PR #11 draft</td><td>D10 보안·권한, D11 운영 매뉴얼</td></tr>
<tr><td>7. 설치본</td><td>10/19 ~ 10/23 (안)</td><td>예정</td><td>자바가 없는 PC 에서 실행</td><td>D11 보완</td></tr>
<tr><td>8. 확장</td><td>10/26 ~ (안)</td><td>예정</td><td>별도 계획(22테이블, PK 없는 테이블, DDL 변경 감지, 대용량)</td><td>—</td></tr>
</tbody>
</table>

<h2>2. 일정을 정한 방법</h2>
<ul>
<li>0~5단계는 PR 머지일 기준 실제 기간이다(v0.1 의 안보다 크게 앞당겨졌다).</li>
<li>6단계부터는 plan.md 에 날짜가 없어 이 문서에서 정한 안이다. 6·7단계 각 1주, 8단계는 별도 계획.</li>
<li>단계가 끝날 때마다 실제 날짜로 고치고 버전을 올린다.</li>
</ul>
"""
