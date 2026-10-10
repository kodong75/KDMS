"""D05 관리 테이블 물리 ERD(IE 표기). 근거: docs/database.md(관리 스키마 v3 정의서), src/main/resources/db/kdms-schema*.sql.

1장 한눈에 보기(v0.1 의 요약 그림, 사용자가 보고 결정하려고 남김), 2장 논리 ERD(엔터티·관계·핵심 속성),
3·4장 물리 ERD(12개 테이블, 112개 컬럼 전체: 3장 작업·적재·동기화, 4장 검증·전환·기록). 순서는 사용자 2026-10-09.
"""

from diagram import C, Diagram

ID = "d05-erd"
TITLE = "관리 테이블 ERD"
VERSION = "v0.4"
DATE = "2026-10-09"

X1, X2, X3, W = 50, 577, 1104, 446   # 세 열 x, 엔터티 폭(열 사이 81px 에 관계선 기호가 들어간다)
TOP = 128

LEGEND = [("ie_one", "정확히 1"), ("ie_zero_one", "0 또는 1"), ("ie_many", "0 이상 (N)"),
          ("ident", "식별 관계"), ("nonident", "비식별 관계")]

# (키, 컬럼, 타입, NOT NULL). database.md §2 의 순서 그대로
JOB = [("PK", "job_id", "bigint", True), ("UK", "job_name", "varchar(100)", True),
       ("", "src_server", "varchar(200)", True), ("", "src_database", "varchar(128)", True),
       ("", "tgt_database", "varchar(63)", True), ("", "status", "varchar(20)", True),
       ("", "config_sha256", "char(64)", False), ("", "created_at", "timestamptz", True),
       ("", "updated_at", "timestamptz", True), ("", "last_error", "text", False)]
JOB_TABLE = [("PK", "job_table_id", "bigint", True), ("FK", "job_id", "bigint", True),
             ("U1", "src_schema", "varchar(128)", True), ("U1", "src_table", "varchar(128)", True),
             ("U2", "tgt_schema", "varchar(63)", True), ("U2", "tgt_table", "varchar(63)", True),
             ("", "has_pk", "boolean", True), ("", "status", "varchar(20)", True),
             ("", "rows_estimate", "bigint", False), ("", "rows_loaded", "bigint", False),
             ("", "load_started_at", "timestamptz", False), ("", "load_finished_at", "timestamptz", False),
             ("", "last_error", "text", False), ("", "applied_commit_lsn", "varchar(24)", False),
             ("", "applied_change_lsn", "varchar(24)", False), ("", "applied_event_serial_no", "bigint", False),
             ("", "changes_applied", "bigint", True)]
LOAD_CHUNK = [("PK", "chunk_id", "bigint", True), ("FK", "job_table_id", "bigint", True),
              ("UK", "chunk_no", "integer", True), ("", "lower_bound", "jsonb", False),
              ("", "upper_bound", "jsonb", False), ("", "status", "varchar(10)", True),
              ("", "row_count", "bigint", False), ("", "elapsed_ms", "bigint", False),
              ("", "attempts", "integer", True), ("", "started_at", "timestamptz", False),
              ("", "finished_at", "timestamptz", False), ("", "last_error", "text", False)]
WATERMARK = [("PK", "job_id (FK)", "bigint", True), ("", "start_lsn", "varchar(24)", True),
             ("", "start_recorded_at", "timestamptz", True), ("", "applied_commit_lsn", "varchar(24)", False),
             ("", "applied_change_lsn", "varchar(24)", False), ("", "applied_event_serial_no", "bigint", False),
             ("", "applied_src_commit_at", "timestamptz", False), ("", "applied_at", "timestamptz", False),
             ("", "stream_lsn", "varchar(24)", False), ("", "src_max_lsn", "varchar(24)", False),
             ("", "src_max_lsn_at", "timestamp", False), ("", "pending_changes", "bigint", False),
             ("", "lag_seconds", "numeric(12,3)", False), ("", "sync_status_at", "timestamptz", False),
             ("", "changes_captured", "bigint", True), ("", "changes_applied", "bigint", True)]
CHANGE_LOG = [("PK", "change_id", "bigint", True), ("FK", "job_id", "bigint", True),
              ("UK", "commit_lsn", "varchar(24)", True), ("UK", "change_lsn", "varchar(24)", True),
              ("UK", "event_serial_no", "bigint", True), ("", "op", "char(1)", True),
              ("", "src_schema", "varchar(128)", True), ("", "src_table", "varchar(128)", True),
              ("", "payload", "jsonb", True), ("", "src_commit_at", "timestamptz", False),
              ("", "received_at", "timestamptz", True)]
VERIFY_RUN = [("PK", "run_id", "bigint", True), ("FK", "job_id", "bigint", True),
              ("", "started_at", "timestamptz", True), ("", "finished_at", "timestamptz", False),
              ("", "checks", "integer", False), ("", "mismatches", "integer", False)]
VERIFY_RESULT = [("PK", "verify_id", "bigint", True), ("FK", "run_id", "bigint", True),
                 ("FK", "job_table_id", "bigint", True), ("", "check_kind", "varchar(10)", True),
                 ("", "check_target", "varchar(128)", True), ("", "src_value", "text", False),
                 ("", "tgt_value", "text", False), ("", "matched", "boolean", True),
                 ("", "checked_at", "timestamptz", True)]
VERIFY_ROW_DIFF = [("PK", "diff_id", "bigint", True), ("FK", "run_id", "bigint", True),
                   ("FK", "job_table_id", "bigint", True), ("", "pk", "jsonb", True),
                   ("", "side", "varchar(8)", True)]
EVENT_LOG = [("PK", "event_id", "bigint", True), ("FK", "job_id", "bigint", False),
             ("", "logged_at", "timestamptz", True), ("", "level", "varchar(5)", True),
             ("", "stage", "varchar(20)", True), ("", "message", "text", True)]
CUTOVER_RUN = [("PK", "cutover_id", "bigint", True), ("FK", "job_id", "bigint", True),
               ("", "started_at", "timestamptz", True), ("", "src_started_at", "timestamp", False),
               ("", "finished_at", "timestamptz", False), ("", "status", "varchar(10)", True),
               ("", "elapsed_ms", "bigint", False), ("", "verify_run_id", "bigint", False),
               ("", "last_error", "text", False)]
CUTOVER_STEP = [("PK", "cutover_id (FK)", "bigint", True), ("PK", "step_no", "integer", True),
                ("", "step", "varchar(20)", True), ("", "status", "varchar(10)", True),
                ("", "started_at", "timestamptz", True), ("", "finished_at", "timestamptz", False),
                ("", "elapsed_ms", "bigint", False), ("", "detail", "text", False)]
SCHEMA_VERSION = [("PK", "version", "integer", True), ("", "description", "text", True),
                  ("", "applied_at", "timestamptz", True)]


def overview() -> Diagram:
    d = Diagram(1600, 900, "KDMS 관리 테이블 ERD (1/4) 한눈에 보기",
                "kdms 스키마 v3 전체를 묶음·상태 값과 함께 요약 · 엔터티 표기는 2장 논리, 3·4장 물리",
                f"KDMS-D05 · {VERSION} · {DATE} · 1/4")
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


def logical() -> Diagram:
    d = Diagram(1600, 900, "KDMS 관리 테이블 논리 ERD (2/4)",
                "엔터티·관계·핵심 속성 · 머리 오른쪽은 물리 테이블 이름 · 컬럼·타입 전체는 3·4장 물리 ERD",
                f"KDMS-D05 · {VERSION} · {DATE} · 2/4")
    d.legend = LEGEND
    w, xs, ys = 315, (50, 445, 840, 1235), (128, 368, 608)

    def ent(eid, c, r, name, table, attrs):
        return d.entity(eid, xs[c], ys[r], w, name, table, [(k, a, "", False) for k, a in attrs])

    ev = ent("ev", 0, 0, "단계 기록", "event_log",
             [("PK", "기록 ID"), ("FK", "작업 ID"), ("", "기록 시각"), ("", "수준 · 단계"), ("", "메시지")])
    wm = ent("wm", 1, 0, "워터마크", "watermark",
             [("PK", "작업 ID (FK)"), ("", "시작 LSN"), ("", "반영 위치 (LSN)"), ("", "지연 · 대기 건수")])
    vr = ent("vr", 2, 0, "검증 실행", "verify_run",
             [("PK", "검증 ID"), ("FK", "작업 ID"), ("", "시작 · 끝 시각"), ("", "검사 수 · 불일치 수")])
    ent("sv", 3, 0, "스키마 버전", "schema_version", [("PK", "버전"), ("", "설명"), ("", "적용 시각")])
    cl = ent("cl", 0, 1, "변경 버퍼", "change_log",
             [("PK", "변경 ID"), ("FK", "작업 ID"), ("UK", "변경 위치 (LSN)"), ("", "변경 종류 (c · u · d)"),
              ("", "원천 테이블"), ("", "변경 값 (반영 뒤 삭제)")])
    job = ent("job", 1, 1, "작업", "job",
              [("PK", "작업 ID"), ("UK", "작업 이름"), ("", "원천 서버 · DB"), ("", "대상 DB"), ("", "작업 상태")])
    vres = ent("vres", 2, 1, "검사 결과", "verify_result",
               [("PK", "검사 ID"), ("FK", "검증 ID"), ("FK", "작업 테이블 ID"), ("", "검사 종류"),
                ("", "원천 값 · 대상 값"), ("", "일치 여부")])
    rd = ent("rd", 3, 1, "불일치 행", "verify_row_diff",
             [("PK", "차이 ID"), ("FK", "검증 ID"), ("FK", "작업 테이블 ID"), ("", "PK 값"),
              ("", "차이 구분 (missing 등)")])
    cr = ent("cr", 0, 2, "전환 실행", "cutover_run",
             [("PK", "전환 ID"), ("FK", "작업 ID"), ("", "시작 · 끝 시각"), ("", "전환 상태"), ("", "소요 시간")])
    cs = ent("cs", 1, 2, "전환 단계", "cutover_step",
             [("PK", "전환 ID (FK)"), ("PK", "단계 번호"), ("", "단계 이름"), ("", "단계 상태"), ("", "소요 시간")])
    jt = ent("jt", 2, 2, "작업 테이블", "job_table",
             [("PK", "작업 테이블 ID"), ("FK", "작업 ID"), ("UK", "원천 스키마 · 테이블"), ("UK", "대상 스키마 · 테이블"),
              ("", "테이블 상태"), ("", "마지막 반영 위치")])
    lc = ent("lc", 3, 2, "적재 구간", "load_chunk",
             [("PK", "구간 ID"), ("FK", "작업 테이블 ID"), ("UK", "구간 번호"), ("", "구간 하한 · 상한"), ("", "구간 상태")])

    c01, c12, c23 = 405, 800, 1195          # 열 사이 통로 x
    jm = job.y + job.h / 2
    d.rel("job", "wm", [job.top(job.x + w / 2), wm.bottom(job.x + w / 2)], child_card="zero_one", ident=True)
    d.rel("job", "cl", [job.left(jm), cl.right(jm)])
    yu, yl = job.y + 40, job.y + job.h - 30
    d.rel("job", "ev", [job.left(yu), (c01, yu), (c01, ev.y + 60), ev.right(ev.y + 60)], parent_card="zero_one")
    d.rel("job", "cr", [job.left(yl), (c01, yl), (c01, cr.y + 50), cr.right(cr.y + 50)])
    d.rel("cr", "cs", [cr.right(cr.y + 110), cs.left(cr.y + 110)], ident=True)
    d.rel("job", "vr", [job.right(yu), (c12, yu), (c12, vr.y + 60), vr.left(vr.y + 60)])
    d.rel("job", "jt", [job.right(yl), (c12, yl), (c12, jt.y + 50), jt.left(jt.y + 50)])
    xv = vr.x + w / 2
    d.rel("vr", "vres", [vr.bottom(xv), vres.top(xv)])
    d.rel("jt", "vres", [jt.top(xv), vres.bottom(xv)])
    gy0 = (vr.y + vr.h + vres.y) / 2 + 6     # 1·2줄 사이
    gy1 = (vres.y + vres.h + jt.y) / 2       # 2·3줄 사이
    xr = rd.x + w / 2
    d.rel("vr", "rd", [vr.right(vr.y + vr.h - 30), (c23, vr.y + vr.h - 30), (c23, gy0), (xr, gy0), rd.top(xr)])
    d.rel("jt", "rd", [jt.top(jt.x + w - 40), (jt.x + w - 40, gy1), (xr, gy1), rd.bottom(xr)])
    d.rel("jt", "lc", [jt.right(jt.y + 110), lc.left(jt.y + 110)])

    d.notes = ["작업 1건 = 원천 DB 1 → 대상 DB 1 · 스키마 버전은 관계 없음",
               "행 값은 변경 버퍼에만, 반영하면 바로 지운다"]
    return d


def page1() -> Diagram:
    d = Diagram(1600, 900, "KDMS 관리 테이블 물리 ERD (3/4) 작업·적재·동기화",
                "대상 PostgreSQL 의 kdms 스키마 · 관리 스키마 v3 · IE 표기 · 컬럼 정의는 docs/database.md",
                f"KDMS-D05 · {VERSION} · {DATE} · 3/4")
    d.legend = LEGEND
    wm = d.entity("watermark", X1, TOP, W, "watermark", "워터마크·반영 위치", WATERMARK)
    cl = d.entity("change_log", X1, TOP + wm.h + 30, W, "change_log", "반영 전 변경", CHANGE_LOG)
    job = d.entity("job", X2, TOP, W, "job", "작업", JOB)
    lc = d.entity("load_chunk", X2, TOP + job.h + 70, W, "load_chunk", "적재 구간", LOAD_CHUNK)
    jt = d.entity("job_table", X3, TOP, W, "job_table", "작업 테이블", JOB_TABLE)

    y = TOP + 70
    d.rel("job", "watermark", [job.left(y), wm.right(y)], child_card="zero_one", ident=True)
    d.rel("job", "job_table", [job.right(y), jt.left(y)])
    yc = cl.y + cl.h / 2
    d.rel("job", "change_log", [job.left(job.y + job.h - 40), (536, job.y + job.h - 40), (536, yc), cl.right(yc)])
    yl = lc.y + 60
    d.rel("job_table", "load_chunk", [jt.left(yl), lc.right(yl)])

    d.block("dbz", X3, jt.y + jt.h + 40, W, 116, "Debezium 표 2개 (FK 없음)",
            ["debezium_offset_<job_id>", "debezium_schema_history_<job_id>", "Debezium 이 만든다 · database.md §2.13"],
            tint=True)
    d.notes = ["U1 · U2 = job_id 를 포함한 복합 UK · UK = FK 컬럼을 포함한 복합 UK",
               "FK 는 모두 ON DELETE CASCADE · NN = NOT NULL"]
    return d


def page2() -> Diagram:
    d = Diagram(1600, 900, "KDMS 관리 테이블 물리 ERD (4/4) 검증·전환·기록",
                "점선 테두리 = 3장 엔터티를 참조로 다시 그림(PK 만) · 컬럼 정의는 docs/database.md",
                f"KDMS-D05 · {VERSION} · {DATE} · 4/4")
    d.legend = LEGEND
    ref = ("", "나머지 컬럼은 3장", "", False)
    vr = d.entity("verify_run", X1, TOP, W, "verify_run", "검증 실행", VERIFY_RUN)
    vres = d.entity("verify_result", X1, TOP + vr.h + 70, W, "verify_result", "검사 항목 결과", VERIFY_RESULT)
    jt = d.entity("job_table", X1, vres.y + vres.h + 70, W, "job_table", "작업 테이블",
                  [JOB_TABLE[0], ref], ref=True)
    job = d.entity("job", X2, TOP, W, "job", "작업", [JOB[0], ref], ref=True)
    ev = d.entity("event_log", X2, TOP + job.h + 70, W, "event_log", "단계 기록", EVENT_LOG)
    vd = d.entity("verify_row_diff", X2, ev.y + ev.h + 70, W, "verify_row_diff", "불일치 행", VERIFY_ROW_DIFF)
    cr = d.entity("cutover_run", X3, TOP, W, "cutover_run", "전환 실행", CUTOVER_RUN)
    cs = d.entity("cutover_step", X3, TOP + cr.h + 70, W, "cutover_step", "전환 단계", CUTOVER_STEP)
    d.entity("schema_version", X3, cs.y + cs.h + 30, W, "schema_version", "스키마 버전", SCHEMA_VERSION)

    y = TOP + 50
    d.rel("job", "verify_run", [job.left(y), vr.right(y)])
    d.rel("job", "cutover_run", [job.right(y), cr.left(y)])
    xe = X2 + W / 2
    d.rel("job", "event_log", [job.bottom(xe), ev.top(xe)], parent_card="zero_one")
    xv = X1 + W / 2
    d.rel("verify_run", "verify_result", [vr.bottom(xv), vres.top(xv)])
    d.rel("job_table", "verify_result", [jt.top(xv), vres.bottom(xv)])
    ya = vr.y + vr.h - 30
    yb = vd.y + 40
    d.rel("verify_run", "verify_row_diff", [vr.right(ya), (520, ya), (520, yb), vd.left(yb)])
    yj = jt.y + jt.h / 2
    yd = vd.y + vd.h - 30
    d.rel("job_table", "verify_row_diff", [jt.right(yj), (548, yj), (548, yd), vd.left(yd)])
    xc = X3 + W / 2
    d.rel("cutover_run", "cutover_step", [cr.bottom(xc), cs.top(xc)], ident=True)

    d.notes = ["event_log.job_id 는 NULL 허용 · verify_run_id 는 FK 아님",
               "FK 는 모두 ON DELETE CASCADE · NN = NOT NULL"]
    return d


def build() -> list[Diagram]:
    return [overview(), logical(), page1(), page2()]


EXPLAIN = """
<h2>1. 읽는 법</h2>
<ul>
<li>1장은 한눈에 보기다. 테이블을 묶음(적재·작업·검증 전환)별로 놓고 키 요약과 상태 코드값을 함께 보인다. 정식 ERD 표기는 아니다.</li>
<li>2장은 논리 ERD 다. 엔터티를 한글 이름으로, 관계와 핵심 속성(식별자·외래 식별자·주요 속성)만 그렸다. 머리 오른쪽 글은 그 엔터티를 구현한 물리 테이블 이름이다.</li>
<li>3·4장은 물리 ERD 다. 대상 PostgreSQL 의 <code>kdms</code> 스키마에 실제로 있는 테이블·컬럼·타입을 그대로 그렸다. 3장 작업·적재·동기화, 4장 검증·전환·기록.</li>
<li>IE(Information Engineering, 정보 공학) 표기. 선 끝 기호로 관계 차수를 읽는다: 두 줄 = 정확히 1, 원 + 한 줄 = 0 또는 1, 원 + 까마귀발 = 0 이상(N). 부모 쪽이 1, 자식(FK 를 가진 쪽)이 N 이다.</li>
<li>실선 = 식별 관계(부모 PK 가 자식 PK 에 들어감: <code>watermark</code>, <code>cutover_step</code>), 점선 = 비식별 관계.</li>
<li>물리 엔터티 박스: 머리 = 테이블 이름과 한글 이름, 구분선 위 = PK, 아래 = 나머지 컬럼. 왼쪽 표시 PK·FK·UK, 오른쪽 NN = NOT NULL(빈칸은 NULL 허용). <code>(FK)</code> 는 PK 이면서 FK 인 컬럼.</li>
<li>4장의 점선 테두리 엔터티(<code>job</code>, <code>job_table</code>)는 3장 엔터티를 관계를 보이려고 다시 그린 것이다.</li>
<li>컬럼 설명·기본값·CHECK·인덱스 전체는 <code>docs/database.md</code> §2 한 곳에 둔다. 이 그림은 그 문서와 같은 순서로 컬럼을 그렸다.</li>
</ul>

<h2>2. 키·인덱스</h2>
<table>
<thead><tr><th>테이블</th><th>PK</th><th>UK</th><th>FK (모두 ON DELETE CASCADE)</th><th>인덱스</th></tr></thead>
<tbody>
<tr><td><code>job</code></td><td>job_id (identity)</td><td>job_name</td><td>—</td><td>—</td></tr>
<tr><td><code>job_table</code></td><td>job_table_id (identity)</td><td>U1 (job_id, src_schema, src_table), U2 (job_id, tgt_schema, tgt_table)</td><td>job_id → job</td><td>ix_job_table_job (job_id)</td></tr>
<tr><td><code>load_chunk</code></td><td>chunk_id (identity)</td><td>(job_table_id, chunk_no)</td><td>job_table_id → job_table</td><td>ix_load_chunk_status (job_table_id, status)</td></tr>
<tr><td><code>watermark</code></td><td>job_id</td><td>—</td><td>job_id → job (식별, 1 : 0..1)</td><td>—</td></tr>
<tr><td><code>change_log</code></td><td>change_id (identity)</td><td>uq_change_log_pos (job_id, commit_lsn, change_lsn, event_serial_no)</td><td>job_id → job</td><td>ix_change_log_apply (job_id, src_schema, src_table, commit_lsn, change_lsn, event_serial_no)</td></tr>
<tr><td><code>verify_run</code></td><td>run_id (identity)</td><td>—</td><td>job_id → job</td><td>—</td></tr>
<tr><td><code>verify_result</code></td><td>verify_id (identity)</td><td>—</td><td>run_id → verify_run, job_table_id → job_table</td><td>ix_verify_result_run (run_id)</td></tr>
<tr><td><code>verify_row_diff</code></td><td>diff_id (identity)</td><td>—</td><td>run_id → verify_run, job_table_id → job_table</td><td>ix_verify_row_diff_run (run_id, job_table_id)</td></tr>
<tr><td><code>event_log</code></td><td>event_id (identity)</td><td>—</td><td>job_id → job (NULL 허용, 0..1 : N)</td><td>ix_event_log_job (job_id, logged_at)</td></tr>
<tr><td><code>cutover_run</code></td><td>cutover_id (identity)</td><td>—</td><td>job_id → job</td><td>ix_cutover_run_job (job_id, cutover_id)</td></tr>
<tr><td><code>cutover_step</code></td><td>(cutover_id, step_no)</td><td>—</td><td>cutover_id → cutover_run (식별)</td><td>—</td></tr>
<tr><td><code>schema_version</code></td><td>version</td><td>—</td><td>—</td><td>—</td></tr>
<tr><td><code>debezium_offset_&lt;job_id&gt;</code></td><td>없음</td><td>—</td><td>—</td><td>Debezium 이 만든다. 저장할 때마다 표 전체를 다시 쓴다</td></tr>
<tr><td><code>debezium_schema_history_&lt;job_id&gt;</code></td><td>(id, history_data_seq)</td><td>—</td><td>—</td><td>Debezium 이 만든다</td></tr>
</tbody>
</table>
<p><code>cutover_run.verify_run_id</code> 는 이 전환에서 돌린 검증을 가리키지만 FK 가 아니다. 버전별 추가 컬럼(v2: <code>job_table.applied_*</code>·<code>changes_applied</code>, <code>watermark</code> 9~16번, v3: <code>cutover_*</code>)은 <code>docs/database.md</code> §2·§4.</p>

<h2>3. 상태 값 (CHECK)</h2>
<ul>
<li><code>job.status</code>: <code>SCHEMA_DONE → LOADING → SYNCING → CUTOVER → VERIFIED → DONE</code>, 전환 단계가 실패하면 <code>FAILED</code>(다시 load·sync·cutover 가능). <code>PLANNED</code> 는 기본값이지만 지금 코드는 쓰지 않는다. <code>kdms reset --yes</code> 는 SCHEMA_DONE 으로 되돌린다.</li>
<li><code>job_table.status</code>: <code>PENDING → LOADING → LOADED</code>, 구간이 실패하면 <code>FAILED</code> 뒤 다시 적재. 반영기는 LOADED 테이블의 변경만 적용한다.</li>
<li><code>load_chunk.status</code>: <code>PENDING → RUNNING → DONE | FAILED</code>. 다시 적재하면 DONE 이 아닌 구간만 처음부터.</li>
<li><code>cutover_run.status</code>: <code>RUNNING · DONE · FAILED</code>, <code>cutover_step.status</code>: 여기에 <code>SKIPPED</code>. <code>change_log.op</code>: <code>c · u · d</code>, <code>verify_result.check_kind</code>: <code>count · sum · hash</code>, <code>verify_row_diff.side</code>: <code>missing · extra · diff</code>, <code>event_log.level</code>: <code>INFO · WARN · ERROR</code>.</li>
<li>상태 전이 그림과 명령별 허용 상태는 <code>docs/database.md</code> §3.</li>
</ul>

<h2>4. 원칙</h2>
<ul>
<li>상태는 대상 PG 한 곳(<code>kdms</code> 스키마)에 둔다. 별도 파일·메시지 브로커가 없다.</li>
<li>행 값(개인정보)·비밀번호를 남기는 컬럼은 없다. 예외는 <code>change_log.payload</code> 이며 반영하면 바로 지운다. <code>verify_row_diff.pk</code> 는 PK 값만, 테이블마다 앞 100건.</li>
<li>정의를 바꾸면 새 버전 SQL 파일을 더하고, 앱이 시작할 때 차례로 적용한다(v1 1단계, v2 4단계, v3 5단계).</li>
</ul>
"""
