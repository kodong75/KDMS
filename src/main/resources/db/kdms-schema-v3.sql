-- KDMS 관리 테이블 버전 3: 5단계 전환(kdms cutover). docs/cutover.md
-- SchemaInstaller 가 버전 2 다음에 한 트랜잭션으로 실행한다. 재실행 안전(IF NOT EXISTS).
-- 행 값은 여기에도 남기지 않는다. 단계·시각·소요 시간·건수·시퀀스 값만.

-- 전환 한 번(kdms cutover 실행 한 번). 다시 실행하면 새 행
CREATE TABLE IF NOT EXISTS kdms.cutover_run (
    cutover_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         bigint      NOT NULL REFERENCES kdms.job (job_id) ON DELETE CASCADE,
    started_at     timestamptz NOT NULL DEFAULT now(),
    src_started_at timestamp,                         -- 시작 때 원천 서버 시각(원천 시계)
    finished_at    timestamptz,
    status         varchar(10) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    elapsed_ms     bigint,                            -- 시작~끝 = 예상 다운타임(plan.md §4.5 ⑦)
    verify_run_id  bigint,                            -- 이 전환에서 돌린 검증(kdms.verify_run)
    last_error     text
);

-- 전환 단계별 기록
CREATE TABLE IF NOT EXISTS kdms.cutover_step (
    cutover_id  bigint      NOT NULL REFERENCES kdms.cutover_run (cutover_id) ON DELETE CASCADE,
    step_no     integer     NOT NULL,
    step        varchar(20) NOT NULL,
    status      varchar(10) NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'SKIPPED')),
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    elapsed_ms  bigint,
    detail      text,
    PRIMARY KEY (cutover_id, step_no)
);

CREATE INDEX IF NOT EXISTS ix_cutover_run_job ON kdms.cutover_run (job_id, cutover_id);

INSERT INTO kdms.schema_version (version, description)
VALUES (3, '5단계 전환: 전환 실행·단계 기록(docs/cutover.md)')
ON CONFLICT (version) DO NOTHING;
