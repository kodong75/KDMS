"""D08 검증 계획서. 근거: docs/load-verify.md §3·§5, docs/normalization.md, docs/plan.md §8, docs/cdc.md §8, docs/cutover.md §6."""

from diagram import C, Diagram

ID = "d08-verify-plan"
TITLE = "검증 계획서"
VERSION = "v0.1"
DATE = "2026-10-09"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 검증 계획서",
                "원천 = 대상을 숫자로 확인 · kdms verify 와 전환의 검증 단계가 같은 절차 · 합격 기준과 시험 항목",
                f"KDMS-D08 · {VERSION} · {DATE}")
    d.legend = [("sync", "처리 순서"), ("async", "불합격(불일치) 시 경로")]

    d.zone(44, 126, 1512, 360, "검증 절차", "테이블마다 · 원천과 대상에서 동시에 계산하고 숫자만 가져온다", core=True)
    d.zone(44, 502, 740, 288, "검사 항목", "행 값은 가져오지 않는다")
    d.zone(800, 502, 756, 288, "시험 항목 (plan.md §8)", "단계마다 통과 기록은 각 단계 문서 · WORKLOG")

    y, h = 210, 108
    d.block("start", 74, y + 32, 160, 44, "verify 시작", shape="start")
    d.block("b1", 280, y, 230, h, "정규화 SQL 생성", ["타입별 식 · 값 규칙"], icon="rule_settings", num="1")
    d.block("b2", 560, y, 240, h, "양쪽 동시 계산", ["원천 T-SQL · 대상 SQL"], icon="database", num="2")
    d.block("b3", 850, y, 210, h, "숫자 비교", ["항목마다 결과 기록"], icon="fact_check", num="3")
    d.block("chk", 1090, y - 6, 220, h + 12, "모두 일치?", ["건수·해시·합계"], shape="diamond")
    d.block("ok", 1380, y + 32, 152, 44, "일치 (종료 0)", shape="start")
    for a, b in (("start", "b1"), ("b1", "b2"), ("b2", "b3"), ("b3", "chk")):
        d.link(a, "r", b, "l")
    d.link("chk", "r", "ok", "l", label="예")

    y2 = 362
    d.block("b4", 1085, y2, 230, 100, "묶음 비교", ["PK MD5 앞 2바이트"], icon="manage_search", num="4")
    d.block("b5", 800, y2, 260, 100, "행 비교", ["missing · extra · diff"], icon="table", num="5")
    d.block("ng", 540, y2 + 28, 220, 44, "불일치 (종료 5)", shape="start")
    d.link("chk", "b", "b4", "t", dashed=True, label="아니오")
    d.link("b4", "l", "b5", "r", dashed=True)
    d.link("b5", "l", "ng", "r", dashed=True)

    for i, (t, sub) in enumerate([("건수", ["COUNT_BIG ↔ count"]),
                                  ("행 해시 합", ["MD5 앞 8바이트 합"]),
                                  ("수치 합계", ["decimal(38, s) 합"])]):
        d.block(f"k{i}", 74 + i * 236, 580, 216, 96, t, sub)
    d.block("rule", 74, 700, 680, 52, "정규화는 표현만 맞춘다 · 값 차이는 해시로 잡는다", icon="rule_settings", pill=True)

    for i, (t, sub, tint) in enumerate([("T-L01 ~ T-L17", ["변환 · 적재 17개 (3단계)"], False),
                                        ("T-C01 ~ T-C12", ["CDC · 전환 12개 (4 · 5단계)"], False),
                                        ("T-N01 ~ T-N03", ["폐쇄망 3개"], False),
                                        ("B01 ~ B14", ["주의 보고 · 합격 기준 아님"], True)]):
        col, row = i % 2, i // 2
        d.block(f"t{i}", 830 + col * 356, 580 + row * 104, 340, 90, t, sub, tint=tint)

    d.notes = [
        "합격 기준: 모든 테이블의 건수·해시·합계 일치(KDMS_MOCK 30항목) · 원천·대상은 읽기만",
        "차이 행은 PK 만 테이블마다 앞 100건(verify_row_diff) · 다른 컬럼 값은 남기지 않는다",
    ]
    return d


EXPLAIN = """
<h2>1. 검사 항목과 합격 기준</h2>
<table>
<thead><tr><th>항목</th><th>원천 (MS-SQL)</th><th>대상 (PG)</th><th>합격</th></tr></thead>
<tbody>
<tr><td>건수</td><td><code>COUNT_BIG(*)</code></td><td><code>count(*)</code></td><td>같음</td></tr>
<tr><td>행 해시 합</td><td>컬럼 정규화 문자열을 <code>|</code> 로 이은 UTF-8 바이트의 MD5 앞 8바이트(bigint) 합</td><td>같은 식(<code>md5(text)</code>)</td><td>같음. 순서와 무관, 한 행이라도 다르면 바뀜</td></tr>
<tr><td>수치 합계</td><td>정수·decimal·money 컬럼마다 <code>SUM(CAST(x AS decimal(38, s)))</code></td><td><code>sum(x)::numeric</code></td><td>값으로 같음(<code>1.50 = 1.5000</code>). float·real·bit 는 해시에만</td></tr>
</tbody>
</table>
<p>작업 하나가 합격하려면 모든 테이블의 모든 항목이 일치해야 한다. KDMS_MOCK 은 7개 테이블 30항목(건수 7, 해시 7, 합계 16)이다. 전환(<code>kdms cutover</code>)은 이 검증이 하나라도 다르면 멈추고 setval·FK 를 하지 않는다.</p>

<h2>2. 정규화 원칙</h2>
<ul>
<li>정규화는 표현(날짜 포맷, money 소수 자릿수, 16진 대소문자 등)만 맞춘다. 끝 공백·대소문자·NUL 손실처럼 실제로 달라진 값은 해시가 달라져야 한다. 예외는 <code>char(n)</code> 채움 공백뿐이다.</li>
<li>규칙 파일이 값을 바꾸면(rtrim · upper · NUL replace · 센티널 → NULL) 원천 쪽에 같은 규칙을 적용해 비교한다. 적재(<code>CopyValues</code>)와 같은 순서다.</li>
<li>NULL 은 <code>\\N</code>. <code>ISNULL</code> 대신 <code>COALESCE</code>(첫 인수 타입으로 잘리는 문제, T-L17).</li>
<li>타입별 식 전체는 <code>docs/normalization.md</code>.</li>
</ul>

<h2>3. 불일치일 때 (행 차이 찾기)</h2>
<ol>
<li>각 행의 PK 정규화 문자열 MD5 앞 2바이트로 묶음 번호(0~65535)를 내고 묶음별 (건수, 해시 합)을 양쪽에서 비교한다. 원천 CI·대상 C 콜레이션처럼 정렬 순서가 달라도 같은 행은 같은 묶음에 든다.</li>
<li>다른 묶음의 행만 (PK, 행 해시)를 가져와 원천에만 = <code>missing</code>, 대상에만 = <code>extra</code>, 해시 다름 = <code>diff</code>.</li>
<li>테이블마다 앞 100건의 PK 만 <code>kdms.verify_row_diff</code> 에 남긴다. 종료 코드 5.</li>
</ol>

<h2>4. 시험 항목</h2>
<table>
<thead><tr><th>묶음</th><th>무엇을 보나</th><th>단계</th><th>기록</th></tr></thead>
<tbody>
<tr><td>T-L01 ~ T-L17</td><td>datetime 3.33ms, datetime2 반올림, money 합계, bit, IDENTITY·SEQUENCE, 끝 공백, NUL, CP949 한글, CI UNIQUE, 계산 컬럼, 센티널 날짜, 이스케이프, 적재 중 강제 종료, ISNULL 금지</td><td>3단계</td><td><code>docs/load-verify.md</code> §5</td></tr>
<tr><td>T-C01 ~ T-C12</td><td>쓰기 중 적재 + 반영 후 검증, 유령 행, 트리거 이력, 적재·CDC 같은 값, LOB 미변경, PK 변경, 강제 종료 후 재시작, SQL Agent 중지, 보존 기간 초과, 전환, 긴 트랜잭션</td><td>4·5단계</td><td><code>docs/cdc.md</code> §8, <code>docs/cutover.md</code> §6</td></tr>
<tr><td>T-N01 ~ T-N03</td><td>네트워크 차단 실행, jar 안 외부 URL 0, 실행 중 외부 연결 0</td><td>6단계</td><td>6단계 리허설</td></tr>
<tr><td>B01 ~ B14</td><td>값은 같지만 조회 결과가 달라지는 곳(대소문자·끝 공백·CP949 바이트 등). 합격·불합격이 아니라 규칙 결정을 보여 준다</td><td>보고서 "주의" 절</td><td><code>kdms plan</code>·<code>kdms verify</code> 출력</td></tr>
</tbody>
</table>
<p>시험 하나하나의 기대 결과는 <code>docs/plan.md</code> §8. 노트북 DB 로 확인한 단계 완료 기록은 WORKLOG 와 <code>runs/</code> 파일이다.</p>
"""
