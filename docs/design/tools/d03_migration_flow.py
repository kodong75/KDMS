"""D03 이관 흐름도. 근거: docs/plan.md §1(이관 순서), §4.2(작업 상태), §4.3~4.5."""

from diagram import Diagram

ID = "d03-migration-flow"
TITLE = "이관 흐름도"
VERSION = "v0.1"
DATE = "2026-10-06"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 이관 흐름도",
                "워터마크 → 전체 적재 → 변경분 반영 → 쓰기 중지 → 마지막 반영 → 검증 → 전환",
                f"KDMS-D03 · {VERSION} · {DATE}")

    d.zone(44, 126, 636, 390, "온라인 구간", "원천 업무는 평소처럼 계속 쓰인다")
    d.zone(704, 126, 846, 390, "다운타임 구간", "원천 쓰기를 멈춘 뒤 · 실제 다운타임 = ④ ~ ⑦", core=True)
    d.zone(44, 536, 1506, 206, "작업 상태 (kdms.job)",
           "어느 단계에서 멈춰도 다시 실행하면 이어서 진행 · 오류는 FAILED 로 남고 원인 수정 후 재실행")

    y, h, w = 205, 150, 186
    d.block("s1", 60, y, w, h, "워터마크 기록", ["CDC 시작 LSN 저장", "적재보다 먼저"], icon="bookmark_flag", num="1")
    d.block("s2", 268, y, w, h, "전체 적재", ["SNAPSHOT · COPY", "구간 병렬 · 수 시간"], icon="database_upload", num="2")
    d.block("s3", 476, y, w, h, "변경분 반영", ["CDC 이벤트 반복 적용", "지연 수 초 ~ 수 분"], icon="sync", num="3", strong=True)
    d.block("s4", 722, y, w, h, "원천 쓰기 중지", ["사람이 업무 중지", "캡처 지연 확인"], icon="pause_circle", num="4")
    d.block("s5", 930, y, w, h, "마지막 반영", ["반영 0건이", "2회 연속될 때까지"], icon="published_with_changes", num="5", strong=True)
    d.block("s6", 1138, y, w, h, "검증", ["건수 · 합계 · 해시", "원천 = 대상"], icon="fact_check", num="6")
    d.block("s7", 1346, y, w, h, "전환", ["setval · FK 켜기", "소요 시간 기록"], icon="swap_horiz", num="7")
    for a, b in (("s1", "s2"), ("s2", "s3"), ("s3", "s4"), ("s4", "s5"), ("s5", "s6"), ("s6", "s7")):
        d.link(a, "r", b, "l")

    d.block("writes", 60, 420, 602, 52, "원천 업무 쓰기 계속", icon="edit_note", pill=True)
    d.link("writes", "t", "s3", "b", dashed=True, a_off=208, label="CDC 로 따라감", label_at=0.5)
    d.block("down", 722, 420, 394, 52, "다운타임 측정: ④ 시작 ~ ⑦ 끝", icon="timer", pill=True)
    d.block("stop", 1138, 384, 186, 116, "불일치 시 멈춤", ["전환하지 않음", "행 차이 목록"], icon="report")
    d.link("s6", "b", "stop", "t", label="불일치")

    states = [("PLANNED", "계획"), ("SCHEMA_DONE", "스키마 생성"), ("LOADING", "전체 적재 중"),
              ("SYNCING", "변경분 반영 중"), ("CUTOVER", "전환 중"), ("VERIFIED", "검증 일치"), ("DONE", "완료")]
    for i, (st, ko) in enumerate(states):
        d.block(f"st{i}", 94 + i * 206, 622, 170, 76, st, [ko], strong=(st in ("SYNCING", "CUTOVER")))
        if i:
            d.link(f"st{i - 1}", "r", f"st{i}", "l")

    d.notes = [
        "④ 쓰기 중지는 사람이 한다(KDMS 는 업무 중지를 대신하지 않는다) · ⑥ 이 맞지 않으면 전환하지 않고 멈춘다",
        "③ 은 테이블별 전체 적재가 끝난 뒤부터 반영한다 · 적재 중 들어온 변경은 워터마크 이후라 모두 change_log 에 있다",
    ]
    return d


EXPLAIN = """
<h2>1. 단계</h2>
<table>
<thead><tr><th>단계</th><th>하는 일</th><th>넘어가는 조건</th><th>근거</th></tr></thead>
<tbody>
<tr><td>① 워터마크 기록</td><td>Debezium 을 데이터 스냅숏 없이(<code>no_data</code>) 띄워 스트리밍이 시작된 LSN 을 <code>watermark.start_lsn</code> 에 저장</td><td>시작 LSN 기록됨. 이후 변경은 모두 <code>change_log</code> 로 들어온다</td><td>plan §4.3-1</td></tr>
<tr><td>② 전체 적재</td><td>SNAPSHOT 격리로 원천을 읽어 COPY. 테이블·PK 구간 병렬, 구간마다 상태 기록</td><td>테이블의 모든 구간 완료(테이블마다 따로)</td><td>plan §4.3</td></tr>
<tr><td>③ 변경분 반영</td><td><code>change_log</code> 를 LSN 순서로 멱등 적용. 적재와 겹친 변경도 다시 적용해 같아진다</td><td>지연이 충분히 작아지면 사람이 전환 시점을 정한다</td><td>plan §4.4</td></tr>
<tr><td>④ 원천 쓰기 중지</td><td>사람이 업무를 멈추고 "전환 시작". 원천 캡처 시각이 전환 시작 시각을 넘을 때까지 기다린다</td><td>캡처 지연이 중지 시각을 넘음</td><td>plan §4.5-1·2</td></tr>
<tr><td>⑤ 마지막 반영</td><td>수집·반영을 반복</td><td>0건이 2회 연속</td><td>plan §4.5-3</td></tr>
<tr><td>⑥ 검증</td><td>건수·합계·행 해시 비교, 불일치 시 PK 구간을 좁혀 행 차이 목록</td><td>전부 일치. 하나라도 다르면 멈추고 전환하지 않는다</td><td>plan §4.5-4, §4.7</td></tr>
<tr><td>⑦ 전환</td><td>IDENTITY·SEQUENCE <code>setval</code>, FK 켜기(검사 포함), 대상 트리거 목록 출력, ④~⑦ 소요 시간 기록</td><td>작업 상태 DONE</td><td>plan §4.5-5~7</td></tr>
</tbody>
</table>

<h2>2. 작업 상태</h2>
<p><code>kdms.job.status</code> 는 <code>PLANNED → SCHEMA_DONE → LOADING → SYNCING → CUTOVER → VERIFIED → DONE</code> 순서로만 움직인다. 오류가 나면 <code>FAILED</code> 와 원문을 <code>event_log</code> 에 남기고, 원인을 고친 뒤 다시 실행하면 끝난 구간·반영 위치부터 이어서 한다(plan §4.2).</p>

<h2>3. 주의</h2>
<ul>
<li>워터마크를 적재보다 먼저 기록해야 적재 중 변경이 빠지지 않는다. 순서를 바꾼 실험 빌드에서는 검증이 차이를 잡아내야 한다(plan §8.2 T-C02).</li>
<li>CDC 수집은 ①부터 ⑤까지 계속 돈다. 수집이 멈춘 채 원천 CDC 보존 기간(기본 3일)이 지나면 오류로 멈추고 "전체 적재부터 다시"를 안내한다(plan §4.4).</li>
<li>PK 없는 테이블은 CDC 반영을 멱등으로 할 수 없어 ⑦ 전에 전체 재적재한다(다운타임에 포함, plan §4.6).</li>
</ul>
"""
