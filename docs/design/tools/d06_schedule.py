"""D06 개발 일정. 근거: docs/plan.md §6(단계·완료 기준), 저장소 PR 이력. 날짜는 안(案)이다."""

from datetime import date, timedelta

from diagram import C, Diagram, text_width

ID = "d06-schedule"
TITLE = "개발 일정"
VERSION = "v0.1"
DATE = "2026-10-06"

TODAY = date(2026, 10, 6)
START = date(2026, 9, 28)          # 첫 주 월요일
WEEKS = 13
X0, X1 = 470, 1550                 # 간트 영역
ROW_Y, ROW_H = 196, 62

DONE, DOING, TODO = "done", "doing", "todo"
FILL = {DONE: C["primary"], DOING: C["sky"], TODO: C["strong_fill"]}
TEXT = {DONE: C["white"], DOING: C["white"], TODO: C["primary"]}

# (단계, 내용, 시작, 끝, 상태, 같이 만들 설계 문서)
ROWS = [
    ("0. 환경 준비", "노트북 DB · CDC 켜기 · 전용 계정", date(2026, 10, 1), date(2026, 10, 6), DONE, ""),
    ("1. 골격", "Maven · CLI · 웹 · 관리 테이블", date(2026, 10, 1), date(2026, 10, 6), DONE, "D01 D02 D03 D06"),
    ("2. 스키마 변환", "kdms plan · DDL (Mac 확인 대기)", date(2026, 10, 1), date(2026, 10, 9), DOING, "D07"),
    ("3. 전체 적재 + 검증", "COPY · 재시작 · 건수·합계·해시", date(2026, 10, 12), date(2026, 10, 23), TODO, "D05 D08"),
    ("4. CDC 수집·반영", "Debezium · change_log · 반영기", date(2026, 10, 26), date(2026, 11, 13), TODO, "D04"),
    ("5. 전환 + 화면·CLI", "상태 기계 · setval · 화면 마무리", date(2026, 11, 16), date(2026, 11, 27), TODO, "D09"),
    ("6. MVP 리허설", "처음부터 2회 · 운영 절차서", date(2026, 11, 30), date(2026, 12, 4), TODO, "D10 D11"),
    ("7. 설치본", "jlink 로 JRE 포함 압축본", date(2026, 12, 7), date(2026, 12, 11), TODO, ""),
    ("8. 확장", "22테이블 · 대용량 · 별도 계획", date(2026, 12, 14), date(2026, 12, 18), TODO, ""),
]


def x_of(d: date) -> float:
    days = (d - START).days
    return X0 + (X1 - X0) * days / (WEEKS * 7)


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 개발 일정",
                "단계별 기간(안) · 단계 끝 = Mac 에서 완료 기준을 확인한 날 · 오늘 2026-10-06",
                f"KDMS-D06 · {VERSION} · {DATE}")
    d.legend = [(C["primary"], "완료"), (C["sky"], "진행 중"), (C["strong_fill"], "예정")]

    top, bottom = 126, ROW_Y + ROW_H * len(ROWS)
    # 머리줄: 월 · 주 시작일
    d.bar(50, top, 1500, 64, C["primary"], rx=8, name="머리줄")
    d.text(70, top + 40, "단계", size=17, bold=True, color=C["white"])
    for m, (ms, me) in {"10월": (date(2026, 10, 1), date(2026, 10, 31)),
                        "11월": (date(2026, 11, 1), date(2026, 11, 30)),
                        "12월": (date(2026, 12, 1), date(2026, 12, 27))}.items():
        d.text((x_of(ms) + x_of(min(me, START + timedelta(weeks=WEEKS)))) / 2, top + 26, m,
               size=16, bold=True, color=C["white"], anchor="middle")
    for w in range(WEEKS):
        wd = START + timedelta(weeks=w)
        d.text(x_of(wd) + (X1 - X0) / WEEKS / 2, top + 52, f"{wd.month}/{wd.day}",
               size=13, color="#D3E5F8", anchor="middle")

    # 줄 배경·주 격자
    for i in range(len(ROWS)):
        if i % 2:
            d.bar(50, ROW_Y + i * ROW_H, 1500, ROW_H, C["zone_fill"], rx=0, name="줄 배경")
    for w in range(WEEKS + 1):
        x = x_of(START + timedelta(weeks=w))
        d.bar(x - 0.5, top + 64, 1, bottom - top - 64, "#D6E3F2", rx=0, name="주 격자")
    d.line(50, bottom, 1550, bottom, color=C["zone_line"], width=1.4)

    # 오늘 선(막대 뒤에 깔리도록 먼저 그린다)
    tx = x_of(TODAY) + (X1 - X0) / (WEEKS * 7) / 2
    d.bar(tx - 1, top + 64, 2, bottom - top - 50, C["navy"], rx=0, name="오늘 선")
    d.text(tx, bottom + 34, "오늘 10/6", size=14, bold=True, color=C["navy"], anchor="middle")

    # 막대
    for i, (name, desc, s, e, st, docs) in enumerate(ROWS):
        y = ROW_Y + i * ROW_H
        d.text(70, y + 28, name, size=17, bold=True, color=C["navy"])
        d.text(70, y + 49, desc, size=13.5, color=C["muted"])
        bx, bw = x_of(s), x_of(e + timedelta(days=1)) - x_of(s)
        label = f"{s.month}/{s.day} ~ {e.month}/{e.day}"
        inside = text_width(label, 13) + 16 <= bw     # 짧은 막대는 날짜를 막대 오른쪽에
        d.bar(bx, y + 15, bw, 32, FILL[st], line=C["primary"] if st == TODO else None, lw=1.2, rx=8,
              text=label if inside else "", text_color=TEXT[st], size=13, name=f"막대 {name}")
        after = bx + bw + 10
        if not inside:
            d.text(after, y + 36, label, size=13, bold=True, color=C["navy"])
            after += text_width(label, 13) + 14
        if docs:
            d.text(after, y + 36, docs, size=13.5, bold=True, color=C["primary"])


    d.notes = [
        "막대 오른쪽 D 번호: 그 단계에서 함께 만드는 설계 문서 · 3단계 이후 날짜는 안(案)으로 단계가 끝날 때마다 고친다",
        "단계마다 브랜치 하나 + draft PR 하나 · 머지는 사용자가 한다(plan §6)",
    ]
    return d


EXPLAIN = """
<h2>1. 단계별 일정(안)</h2>
<table>
<thead><tr><th>단계</th><th>기간(안)</th><th>상태</th><th>완료 기준 (plan §6)</th><th>함께 만들 문서</th></tr></thead>
<tbody>
<tr><td>0. 환경 준비</td><td>10/1 ~ 10/6</td><td>완료</td><td>Mac 에서 1433·5432 접속, <code>KDMS_MOCK</code> CDC 켜짐, <code>kdms_app</code> 로그인</td><td>—</td></tr>
<tr><td>1. 골격</td><td>10/1 ~ 10/6</td><td>완료</td><td><code>kdms status</code> 원천·대상 OK(Mac 확인 2026-10-06), 오프라인 빌드, 라이선스 보고서</td><td>D01 구성도, D02 아키텍처, D03 흐름도, D06 일정</td></tr>
<tr><td>2. 스키마 변환</td><td>10/1 ~ 10/9</td><td>진행 중</td><td><code>KDMS_MOCK</code> 대상 DDL 이 KIS 정의와 같은 타입(차이는 규칙 파일로 설명). Mac 확인 대기(PR #4)</td><td>D07 변환 규칙 매핑표</td></tr>
<tr><td>3. 전체 적재 + 검증</td><td>10/12 ~ 10/23</td><td>예정</td><td>쓰기 없는 상태에서 전 테이블 검증 일치, 적재 중 강제 종료 후 이어서 완료</td><td>D05 관리 테이블 ERD, D08 검증 계획서</td></tr>
<tr><td>4. CDC 수집·반영</td><td>10/26 ~ 11/13</td><td>예정</td><td>쓰기 부하 중 적재 → 반영, 쓰기 중지 후 검증 일치</td><td>D04 데이터 흐름도</td></tr>
<tr><td>5. 전환 + 화면·CLI</td><td>11/16 ~ 11/27</td><td>예정</td><td>시나리오 S1~S4 통과, 전환 소요 시간 보고</td><td>D09 전환·롤백 절차서</td></tr>
<tr><td>6. MVP 리허설</td><td>11/30 ~ 12/4</td><td>예정</td><td>처음부터 2회 모두 합격, 절차서만 보고 다시 할 수 있음</td><td>D10 보안·권한, D11 운영 매뉴얼</td></tr>
<tr><td>7. 설치본</td><td>12/7 ~ 12/11</td><td>예정</td><td>자바가 없는 PC 에서 실행</td><td>D11 보완</td></tr>
<tr><td>8. 확장</td><td>12/14 ~</td><td>예정</td><td>별도 계획(22테이블, PK 없는 테이블, DDL 변경 감지, 대용량)</td><td>—</td></tr>
</tbody>
</table>

<h2>2. 일정을 정한 방법</h2>
<ul>
<li>0~2단계는 저장소 PR 이력 기준이다(2026-10-01 계획 머지, 10-06 1단계 Mac 확인).</li>
<li>3단계부터는 plan.md 에 날짜가 없어 이 문서에서 처음 정한 안이다. 기능 규모로 3단계 2주, 4단계(가장 위험이 큼, plan §7 R3~R5) 3주, 5단계 2주, 6·7단계 각 1주로 잡았다.</li>
<li>단계가 끝날 때마다 실제 날짜로 고치고 버전을 올린다.</li>
</ul>
"""
