"""D05 관리 테이블 ERD. 근거: docs/database.md(관리 스키마 v3 정의서), src/main/resources/db/kdms-schema*.sql."""

from diagram import C, Diagram

ID = "d05-erd"
TITLE = "관리 테이블 ERD"
VERSION = "v0.1"
DATE = "2026-10-09"


def build() -> Diagram:
    d = Diagram(1600, 900, "KDMS 관리 테이블 ERD",
                "대상 PostgreSQL 의 kdms 스키마 · 관리 스키마 v3 · 정의는 docs/database.md",
                f"KDMS-D05 · {VERSION} · {DATE}")
    d.legend = [("sync", "FK 참조 (자식 → 부모, 1 : N)"), (C["tint_fill"], "상태 코드값")]

    d.zone(44, 126, 482, 664, "적재 · 기타", "3단계 적재 구간, FK 없는 표")
    d.zone(560, 126, 480, 664, "작업 · 변경분", "작업 1건 = 원천 DB 1 → 대상 DB 1", core=True)
    d.zone(1074, 126, 482, 664, "검증 · 전환", "3단계 검증, 5단계 전환 기록")

    h = 90
    # 작업·변경분
    d.block("job", 590, 204, 420, h, "job", ["PK job_id · UK job_name", "status · config_sha256 · last_error"], strong=True)
    d.block("wm", 586, 352, 136, h, "watermark", ["PK = job_id", "LSN · 지연"])
    d.block("cl", 732, 352, 136, h, "change_log", ["반영 전 변경", "반영 뒤 삭제"])
    d.block("ev", 878, 352, 136, h, "event_log", ["단계 기록", "행 값 없음"])
    d.link("wm", "t", "job", "b", b_off=-146)
    d.link("cl", "t", "job", "b")
    d.link("ev", "t", "job", "b", b_off=146)
    d.block("st1", 590, 500, 420, h, "job.status",
            ["SCHEMA_DONE → LOADING → SYNCING", "→ CUTOVER → VERIFIED → DONE · FAILED"], tint=True)
    d.block("st2", 590, 640, 420, h, "job_table.status",
            ["PENDING → LOADING → LOADED", "구간 실패 → FAILED → 다시 LOADING"], tint=True)

    # 적재·기타
    d.block("jt", 74, 204, 422, h, "job_table", ["PK job_table_id · FK job_id", "status · 테이블별 반영 위치(LSN)"])
    d.block("lc", 74, 352, 422, h, "load_chunk", ["PK chunk_id · FK job_table_id", "구간 상·하한(jsonb) · status"])
    d.link("jt", "r", "job", "l")
    d.link("lc", "t", "jt", "b")
    d.block("sv", 74, 500, 422, h, "schema_version", ["적용한 관리 스키마 버전 1 · 2 · 3", "FK 없음"])
    d.block("dbz", 74, 640, 422, h, "debezium_offset · schema_history",
            ["Debezium 이 만든다 · 작업마다 따로", "FK 없음 · reset 이 지운다"])

    # 검증·전환
    d.block("vr", 1104, 204, 422, h, "verify_run", ["PK run_id · FK job_id", "checks · mismatches"])
    d.block("vres", 1104, 352, 206, h, "verify_result", ["건수·합계·해시", "숫자만"])
    d.block("vdiff", 1320, 352, 206, h, "verify_row_diff", ["PK 값만", "missing·extra·diff"])
    d.link("vr", "l", "job", "r")
    d.link("vres", "t", "vr", "b", b_off=-108)
    d.link("vdiff", "t", "vr", "b", b_off=108)
    d.block("cr", 1104, 500, 422, h, "cutover_run", ["PK cutover_id · FK job_id", "elapsed_ms = 예상 다운타임"])
    d.block("cs", 1104, 640, 422, h, "cutover_step", ["PK (cutover_id, step_no)", "drain → reload → … → fk"])
    d.link("cs", "t", "cr", "b")
    d.link("cr", "l", "job", "r", b_off=24)

    d.notes = [
        "verify_result · verify_row_diff 는 job_table 도 참조(선 생략) · FK 는 모두 ON DELETE CASCADE",
        "행 값은 change_log.payload 에만 두고 반영하면 바로 지운다 · 비밀번호 컬럼 없음",
    ]
    return d


EXPLAIN = """
<h2>1. 테이블</h2>
<p>컬럼·타입·키·인덱스 전체는 <code>docs/database.md</code> 에 한 곳으로 둔다(SQL 파일을 옮긴 정의서). 이 표는 그림을 읽기 위한 요약이다.</p>
<table>
<thead><tr><th>표</th><th>한 행</th><th>부모(FK)</th><th>버전</th><th>쓰는 명령</th></tr></thead>
<tbody>
<tr><td><code>job</code></td><td>작업 1건(원천 DB 1 → 대상 DB 1). 작업 상태</td><td>—</td><td>v1</td><td>schema · load · sync · cutover · reset</td></tr>
<tr><td><code>job_table</code></td><td>작업에 든 테이블. 테이블 상태, 적재 행, 테이블별 마지막 반영 위치</td><td>job</td><td>v1 (반영 위치 v2)</td><td>load · sync</td></tr>
<tr><td><code>load_chunk</code></td><td>전체 적재 구간. 구간 행과 DONE 표시를 한 트랜잭션으로 커밋</td><td>job_table</td><td>v1</td><td>load</td></tr>
<tr><td><code>watermark</code></td><td>작업마다 한 행. 워터마크(시작 LSN), 반영 위치, 지연·대기</td><td>job</td><td>v1 (진행 상태 v2)</td><td>sync</td></tr>
<tr><td><code>change_log</code></td><td>반영 전 변경. <code>payload</code> 는 반영하면 바로 지운다</td><td>job</td><td>v1</td><td>sync</td></tr>
<tr><td><code>verify_run</code></td><td>검증 한 번(<code>kdms verify</code>, 전환의 검증 단계)</td><td>job</td><td>v1</td><td>verify · cutover</td></tr>
<tr><td><code>verify_result</code></td><td>검사 항목(건수·합계·해시) 하나의 원천·대상 값</td><td>verify_run, job_table</td><td>v1</td><td>verify · cutover</td></tr>
<tr><td><code>verify_row_diff</code></td><td>불일치 행의 PK(테이블마다 앞 100건)</td><td>verify_run, job_table</td><td>v1</td><td>verify · cutover</td></tr>
<tr><td><code>event_log</code></td><td>단계 시작·끝·오류(행 값 없음)</td><td>job</td><td>v1</td><td>모든 명령</td></tr>
<tr><td><code>cutover_run</code></td><td>전환 한 번. 소요 시간 = 예상 다운타임</td><td>job</td><td>v3</td><td>cutover</td></tr>
<tr><td><code>cutover_step</code></td><td>전환 단계 6개(drain · reload · post_load · verify · setval · fk)</td><td>cutover_run</td><td>v3</td><td>cutover</td></tr>
<tr><td><code>schema_version</code></td><td>적용한 관리 스키마 버전</td><td>—</td><td>v1</td><td>시작할 때 자동</td></tr>
<tr><td><code>debezium_offset_&lt;job_id&gt;</code>, <code>debezium_schema_history_&lt;job_id&gt;</code></td><td>Debezium 오프셋·스키마 이력. Debezium 이 만든다</td><td>—</td><td>—</td><td>sync · reset</td></tr>
</tbody>
</table>

<h2>2. 상태 값</h2>
<ul>
<li><code>job.status</code>: <code>SCHEMA_DONE → LOADING → SYNCING → CUTOVER → VERIFIED → DONE</code>, 전환 단계가 실패하면 <code>FAILED</code>(다시 load·sync·cutover 가능). <code>PLANNED</code> 는 기본값이지만 지금 코드는 쓰지 않는다. <code>kdms reset --yes</code> 는 SCHEMA_DONE 으로 되돌린다.</li>
<li><code>job_table.status</code>: <code>PENDING → LOADING → LOADED</code>, 구간이 실패하면 <code>FAILED</code> 뒤 다시 적재. 반영기는 LOADED 테이블의 변경만 적용한다.</li>
<li><code>load_chunk.status</code>: <code>PENDING → RUNNING → DONE | FAILED</code>. 다시 적재하면 DONE 이 아닌 구간만 처음부터.</li>
<li>상태 전이 그림과 명령별 허용 상태는 <code>docs/database.md</code> §3.</li>
</ul>

<h2>3. 원칙</h2>
<ul>
<li>상태는 대상 PG 한 곳(<code>kdms</code> 스키마)에 둔다. 별도 파일·메시지 브로커가 없다.</li>
<li>행 값(개인정보)·비밀번호를 남기는 컬럼은 없다. 예외는 <code>change_log.payload</code> 이며 반영하면 바로 지운다.</li>
<li>정의를 바꾸면 새 버전 SQL 파일을 더하고, 앱이 시작할 때 차례로 적용한다(v1 1단계, v2 4단계, v3 5단계).</li>
</ul>
"""
