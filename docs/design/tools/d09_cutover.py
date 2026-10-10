"""D09 전환·롤백 절차서. 근거: docs/cutover.md §1·§2·§6, docs/plan.md §3.2·§4.5, docs/cdc.md §5."""

from diagram import Diagram

ID = "d09-cutover"
TITLE = "전환·롤백 절차서"
VERSION = "v0.1"
DATE = "2026-10-09"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 전환·롤백 절차서",
                "사람이 쓰기를 멈춘다 → kdms cutover --yes 가 6단계를 자동으로 → 사람이 앱을 PG 로 옮긴다",
                f"KDMS-D09 · {VERSION} · {DATE}")
    d.legend = [("sync", "처리 순서"), ("async", "불합격 시 되돌아가는 경로")]

    d.zone(44, 126, 1512, 190, "준비 · 쓰기 중지 (사람)", "2 부터 다운타임")
    d.zone(44, 332, 1512, 280, "kdms cutover --yes (자동)",
           "다시 실행해도 같은 결과 · 중간에 죽으면 그대로 다시 실행(처음 단계부터)", core=True)
    d.zone(44, 628, 1512, 162, "전환 뒤 · 되돌리기", "")

    # 1줄: 사람
    y, h, w = 196, 104, 250
    d.block("start", 74, y + 30, 160, 44, "전환 준비", shape="start")
    d.block("b1", 280, y, w, h, "지연 확인", ["kdms status · 웹 화면"], icon="timer", num="1")
    d.block("b2", 580, y, w, h, "원천 앱 쓰기 중지", ["다운타임 시작"], icon="pause_circle", num="2")
    d.block("b3", 880, y, w, h, "kdms sync 중지", ["Ctrl+C · 배치 마치고"], icon="sync", num="3")
    d.block("a1", 1190, y + 30, 44, 44, "A", shape="point")
    for a, b in (("start", "b1"), ("b1", "b2"), ("b2", "b3"), ("b3", "a1")):
        d.link(a, "r", b, "l")

    # 2줄: cutover
    y = 404
    d.block("a2", 74, y + 32, 44, 44, "A", shape="point")
    d.block("s1", 160, y, 240, 108, "마지막 반영", ["drain · 최대 600초"], icon="published_with_changes", num="4", strong=True)
    d.block("s2", 450, y, 250, 108, "재적재 · 인덱스", ["PK 없는 표 · UNIQUE"], icon="database_upload", num="5")
    d.block("chk", 745, y - 6, 250, 120, "6. 검증 일치?", ["건수·해시·합계"], shape="diamond")
    d.block("s4", 1040, y, 240, 108, "setval · FK", ["IDENTITY · SEQUENCE"], icon="account_tree", num="7")
    d.block("b1x", 1330, y + 32, 44, 44, "B", shape="point")
    for a, b in (("a2", "s1"), ("s1", "s2"), ("s2", "chk")):
        d.link(a, "r", b, "l")
    d.link("chk", "r", "s4", "l", label="예")
    d.link("s4", "r", "b1x", "l")
    d.block("stop", 745, 540, 250, 62, "멈춤 (종료 5)", ["FAILED · setval·FK 안 함"], tint=True)
    d.link("chk", "b", "stop", "t", dashed=True, label="아니오")
    d.link("stop", "l", "s1", "b", dashed=True, label="고친 뒤 다시 cutover")

    # 3줄: 전환 뒤 · 되돌리기
    y = 668
    d.block("b2x", 74, y + 28, 44, 44, "B", shape="point")
    d.block("s5", 160, y, 260, 100, "앱을 PG 로 전환", ["사람 · 다운타임 끝"], icon="swap_horiz", num="8")
    d.block("done", 470, y + 28, 170, 44, "전환 끝 (DONE)", shape="start")
    d.link("b2x", "r", "s5", "l")
    d.link("s5", "r", "done", "l")
    d.block("back", 745, y, 250, 100, "원천으로 되돌리기", ["앱 쓰기 다시 열기", "kdms sync 이어 가기"], tint=True)
    d.link("stop", "b", "back", "t", dashed=True, label="못 고치면")
    d.block("warn", 1040, y, 490, 100, "앱을 PG 로 옮긴 뒤에는",
            ["역방향 동기화 없음 (MVP 범위 밖)", "되돌리면 그 뒤 PG 에 쓴 데이터는 안 옮겨진다"])

    d.notes = [
        "4 가 --max-wait(기본 600초) 안에 안 끝나면 종료 6 · 다운타임 = 2 ~ 8, kdms 가 재는 것은 4 ~ 7",
        "원천은 읽기만 했으므로 8 전까지는 원천으로 돌아가도 원천 데이터는 그대로다",
    ]
    return d


EXPLAIN = """
<h2>1. 절차</h2>
<table>
<thead><tr><th>번호</th><th>누가</th><th>하는 일</th><th>확인·중단 조건</th></tr></thead>
<tbody>
<tr><td>1</td><td>사람</td><td><code>kdms status</code>·웹 화면에서 지연이 0 근처로 유지되는지 본다</td><td>적재가 안 끝난 테이블이 있으면 전환하지 않는다(cutover 가 거부)</td></tr>
<tr><td>2</td><td>사람</td><td>원천 앱 쓰기를 멈춘다(다운타임 시작). KDMS 는 앱을 멈추지 않는다</td><td><code>--yes</code> 가 이 확인이다</td></tr>
<tr><td>3</td><td>사람</td><td><code>kdms sync</code> 를 Ctrl+C 로 멈춘다(진행 중 배치를 마치고 멈춤). 웹 화면의 "전환 시작" 은 이것을 먼저 한다</td><td>—</td></tr>
<tr><td>4</td><td>cutover</td><td>마지막 반영(<code>kdms sync --drain</code> 과 같음): 캡처 Job 이 시작 뒤 로그를 다 훑었고, 마지막 커밋까지 반영했고, 그 뒤 커밋이 없으면 끝</td><td><code>--max-wait</code>(기본 600초) 초과 → 종료 6. 쓰기가 남아 있다는 뜻</td></tr>
<tr><td>5</td><td>cutover</td><td>PK 없는 테이블 비우고 다시 적재, 미뤄 둔 UNIQUE·인덱스 생성</td><td>실패 → FAILED, 종료 5</td></tr>
<tr><td>6</td><td>cutover</td><td>검증(<code>kdms verify</code> 와 같음). 통과하면 작업 VERIFIED</td><td><b>불일치면 여기서 멈춘다</b>. setval·FK 를 하지 않는다. 종료 5</td></tr>
<tr><td>7</td><td>cutover</td><td>IDENTITY·SEQUENCE <code>setval</code>(원천 값을 이 자리에서 다시 읽음), FK 생성(기존 행도 검사). 작업 DONE, 소요 시간 출력</td><td>실패 → FAILED, 종료 5</td></tr>
<tr><td>8</td><td>사람</td><td>앱 접속을 대상 PG 로 바꾼다(다운타임 끝). 원천 트리거·뷰·SP 목록(전환 뒤 할 일)을 확인한다</td><td>—</td></tr>
</tbody>
</table>
<p>다운타임 = 2 ~ 8. KDMS 가 <code>kdms.cutover_run.elapsed_ms</code> 로 재는 것은 cutover 시작 ~ 끝(4 ~ 7)이고, 사람이 하는 2·3·8 의 시간이 더해진다.</p>

<h2>2. 되돌리기 (롤백)</h2>
<table>
<thead><tr><th>언제</th><th>방법</th><th>데이터</th></tr></thead>
<tbody>
<tr><td>cutover 가 멈춤(종료 5·6), 원인을 바로 고칠 수 있음</td><td>고친 뒤 <code>kdms cutover --yes</code> 다시. 예: 대상 행이 다르면 <code>kdms load --reset -t 그 테이블</code> 뒤 다시(S2)</td><td>원천 그대로, 대상은 다시 맞춘다</td></tr>
<tr><td>cutover 가 멈춤, 원인을 바로 못 고침</td><td>원천 앱 쓰기를 다시 연다. 동기화가 다시 필요하면 <code>kdms sync</code> 로 이어 간다</td><td>원천은 읽기만 했으므로 그대로. 다운타임만 끝난다</td></tr>
<tr><td>8 (앱 전환) 뒤</td><td>역방향 동기화(PG → MS-SQL)는 MVP 범위 밖이다(plan.md §3.2)</td><td>원천으로 돌아가면 전환 뒤 PG 에 쓴 데이터는 옮겨지지 않는다. 그래서 7 까지 통과한 뒤에 8 을 한다</td></tr>
</tbody>
</table>

<h2>3. 시나리오 (5단계 완료 기준)</h2>
<ul>
<li>S1 정상 전환: 종료 코드 0, 검증 30/30, DONE, 소요 시간 출력.</li>
<li>S2 불일치면 전환하지 않음: 6 에서 멈추고 FK 0개, 고친 뒤 다시 하면 DONE.</li>
<li>S3 중단과 재실행: 쓰기가 남으면 종료 6, 6 에서 강제 종료해도 다시 실행하면 DONE.</li>
<li>S4 전환 뒤 새 입력: 새 id = 원천 <code>IDENT_CURRENT + 1</code>, FK 위반은 오류.</li>
<li>정의와 결과는 <code>docs/cutover.md</code> §6.</li>
</ul>
"""
