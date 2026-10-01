-- KDMS 관리 테이블 (대상 PG 의 kdms 스키마), 버전 1. plan.md §4.2
-- SchemaInstaller 가 한 트랜잭션으로 실행한다. 재실행 안전(IF NOT EXISTS).
-- 여기에는 행 값(개인정보)·비밀번호를 남기는 컬럼을 두지 않는다. 예외는 change_log.payload 이며 반영·검증 뒤 지운다(R8).
-- 바꿀 때는 이 파일을 고치지 말고 다음 버전 파일(kdms-schema-v2.sql …)을 추가한다.
-- Debezium 오프셋·스키마 이력 표(kdms.debezium_offset, kdms.debezium_schema_history)는 debezium-storage-jdbc 가 4단계에서 직접 만든다.

CREATE SCHEMA IF NOT EXISTS kdms;

CREATE TABLE IF NOT EXISTS kdms.schema_version (
    version      integer      NOT NULL PRIMARY KEY,
    description  text         NOT NULL,
    applied_at   timestamptz  NOT NULL DEFAULT now()
);

-- 작업 1건 = 원천 DB 1개 → 대상 DB 1개
CREATE TABLE IF NOT EXISTS kdms.job (
    job_id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name       varchar(100) NOT NULL UNIQUE,
    src_server     varchar(200) NOT NULL,          -- host:port (비밀번호 없음)
    src_database   varchar(128) NOT NULL,
    tgt_database   varchar(63)  NOT NULL,
    status         varchar(20)  NOT NULL DEFAULT 'PLANNED'
                   CHECK (status IN ('PLANNED', 'SCHEMA_DONE', 'LOADING', 'SYNCING', 'CUTOVER', 'VERIFIED', 'DONE', 'FAILED')),
    config_sha256  char(64),                       -- 실행에 쓴 설정+규칙 파일 해시. 바뀌면 재시작 시 경고
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    last_error     text
);

-- 작업에 들어간 테이블
CREATE TABLE IF NOT EXISTS kdms.job_table (
    job_table_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id           bigint       NOT NULL REFERENCES kdms.job (job_id) ON DELETE CASCADE,
    src_schema       varchar(128) NOT NULL,
    src_table        varchar(128) NOT NULL,
    tgt_schema       varchar(63)  NOT NULL,
    tgt_table        varchar(63)  NOT NULL,
    has_pk           boolean      NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'LOADING', 'LOADED', 'SYNCING', 'VERIFIED', 'FAILED', 'EXCLUDED')),
    rows_estimate    bigint,
    rows_loaded      bigint,
    load_started_at  timestamptz,
    load_finished_at timestamptz,
    last_error       text,
    UNIQUE (job_id, src_schema, src_table),
    UNIQUE (job_id, tgt_schema, tgt_table)
);

-- 전체 적재 구간. 경계값은 PK 컬럼 순서의 JSON 배열(복합 PK 대응). 하한 포함, 상한 미포함, NULL = 끝 없음
CREATE TABLE IF NOT EXISTS kdms.load_chunk (
    chunk_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_table_id  bigint      NOT NULL REFERENCES kdms.job_table (job_table_id) ON DELETE CASCADE,
    chunk_no      integer     NOT NULL,
    lower_bound   jsonb,
    upper_bound   jsonb,
    status        varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    row_count     bigint,
    elapsed_ms    bigint,
    attempts      integer     NOT NULL DEFAULT 0,
    started_at    timestamptz,
    finished_at   timestamptz,
    last_error    text,
    UNIQUE (job_table_id, chunk_no)
);

-- LSN(Log Sequence Number, 로그 순번)은 Debezium 표기 그대로 'xxxxxxxx:xxxxxxxx:xxxx'(고정 폭 16진수 소문자) → 문자열 비교 = LSN 순서
CREATE TABLE IF NOT EXISTS kdms.watermark (
    job_id                  bigint      NOT NULL PRIMARY KEY REFERENCES kdms.job (job_id) ON DELETE CASCADE,
    start_lsn               varchar(24) NOT NULL,      -- 전체 적재 전에 기록한 스트리밍 시작 위치
    start_recorded_at       timestamptz NOT NULL DEFAULT now(),
    applied_commit_lsn      varchar(24),               -- 마지막으로 대상에 반영한 이벤트
    applied_change_lsn      varchar(24),
    applied_event_serial_no bigint,
    applied_src_commit_at   timestamptz,               -- 그 이벤트의 원천 커밋 시각(지연 계산용)
    applied_at              timestamptz
);

-- Debezium 이벤트 원문. (commit_lsn, change_lsn, event_serial_no) 로 중복 수신 무시, 같은 순서로 반영
CREATE TABLE IF NOT EXISTS kdms.change_log (
    change_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id           bigint       NOT NULL REFERENCES kdms.job (job_id) ON DELETE CASCADE,
    commit_lsn       varchar(24)  NOT NULL,
    change_lsn       varchar(24)  NOT NULL,
    event_serial_no  bigint       NOT NULL,
    op               char(1)      NOT NULL CHECK (op IN ('c', 'u', 'd')),
    src_schema       varchar(128) NOT NULL,
    src_table        varchar(128) NOT NULL,
    payload          jsonb        NOT NULL,           -- 행 값이 들어 있다. 반영·검증 뒤 지운다(R8)
    src_commit_at    timestamptz,
    received_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_change_log_pos UNIQUE (job_id, commit_lsn, change_lsn, event_serial_no)
);

CREATE TABLE IF NOT EXISTS kdms.verify_run (
    run_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id       bigint      NOT NULL REFERENCES kdms.job (job_id) ON DELETE CASCADE,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    checks       integer,
    mismatches   integer
);

CREATE TABLE IF NOT EXISTS kdms.verify_result (
    verify_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id        bigint       NOT NULL REFERENCES kdms.verify_run (run_id) ON DELETE CASCADE,
    job_table_id  bigint       NOT NULL REFERENCES kdms.job_table (job_table_id) ON DELETE CASCADE,
    check_kind    varchar(10)  NOT NULL CHECK (check_kind IN ('count', 'sum', 'hash')),
    check_target  varchar(128) NOT NULL DEFAULT '',   -- sum 이면 컬럼 이름
    src_value     text,                               -- 건수·합계·해시 합(숫자). 행 값은 넣지 않는다
    tgt_value     text,
    matched       boolean      NOT NULL,
    checked_at    timestamptz  NOT NULL DEFAULT now()
);

-- 불일치 행. PK 값만(나머지 컬럼 값은 남기지 않는다)
CREATE TABLE IF NOT EXISTS kdms.verify_row_diff (
    diff_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id        bigint      NOT NULL REFERENCES kdms.verify_run (run_id) ON DELETE CASCADE,
    job_table_id  bigint      NOT NULL REFERENCES kdms.job_table (job_table_id) ON DELETE CASCADE,
    pk            jsonb       NOT NULL,
    side          varchar(8)  NOT NULL CHECK (side IN ('missing', 'extra', 'diff'))
);

-- 단계 시작·끝·오류. 행 값·비밀번호는 쓰지 않는다
CREATE TABLE IF NOT EXISTS kdms.event_log (
    event_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id      bigint      REFERENCES kdms.job (job_id) ON DELETE CASCADE,
    logged_at   timestamptz NOT NULL DEFAULT now(),
    level       varchar(5)  NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    stage       varchar(20) NOT NULL,
    message     text        NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_job_table_job       ON kdms.job_table (job_id);
CREATE INDEX IF NOT EXISTS ix_load_chunk_status   ON kdms.load_chunk (job_table_id, status);
CREATE INDEX IF NOT EXISTS ix_verify_result_run   ON kdms.verify_result (run_id);
CREATE INDEX IF NOT EXISTS ix_verify_row_diff_run ON kdms.verify_row_diff (run_id, job_table_id);
CREATE INDEX IF NOT EXISTS ix_event_log_job       ON kdms.event_log (job_id, logged_at);

INSERT INTO kdms.schema_version (version, description)
VALUES (1, '관리 테이블 최초 생성(plan.md §4.2)')
ON CONFLICT (version) DO NOTHING;
