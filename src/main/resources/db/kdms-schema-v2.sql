-- KDMS 관리 테이블 버전 2: 4단계 CDC(변경분 수집·반영). docs/cdc.md
-- SchemaInstaller 가 버전 1 다음에 한 트랜잭션으로 실행한다. 재실행 안전(IF NOT EXISTS).
-- 행 값은 여기에도 남기지 않는다. LSN(Log Sequence Number, 로그 순번)·시각·건수만.

-- 테이블마다 마지막으로 반영한 변경의 위치. 반영기는 테이블별로 LSN 순서를 지키고(다른 테이블은 서로 기다리지 않는다),
-- 수집기는 이 위치 이하의 변경이 다시 오면(재시작 때 Debezium 이 마지막 오프셋 뒤를 다시 보냄) 버린다.
ALTER TABLE kdms.job_table ADD COLUMN IF NOT EXISTS applied_commit_lsn      varchar(24);
ALTER TABLE kdms.job_table ADD COLUMN IF NOT EXISTS applied_change_lsn      varchar(24);
ALTER TABLE kdms.job_table ADD COLUMN IF NOT EXISTS applied_event_serial_no bigint;
ALTER TABLE kdms.job_table ADD COLUMN IF NOT EXISTS changes_applied         bigint NOT NULL DEFAULT 0;

-- kdms sync 가 status_seconds 마다 쓰는 진행 상태(화면·kdms status 가 읽는다)
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS stream_lsn       varchar(24);  -- Debezium 이 처리한 위치(하트비트·이벤트 오프셋)
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS src_max_lsn      varchar(24);  -- 원천 CDC 의 마지막 트랜잭션 LSN
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS src_max_lsn_at   timestamp;    -- 그 커밋 시각(원천 서버 시계)
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS pending_changes  bigint;       -- 수집했지만 아직 반영하지 않은 변경 수
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS lag_seconds      numeric(12,3);
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS sync_status_at   timestamptz;
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS changes_captured bigint NOT NULL DEFAULT 0;
ALTER TABLE kdms.watermark ADD COLUMN IF NOT EXISTS changes_applied  bigint NOT NULL DEFAULT 0;

-- 반영기가 테이블별로 LSN 순서대로 읽는다
CREATE INDEX IF NOT EXISTS ix_change_log_apply ON kdms.change_log (job_id, src_schema, src_table, commit_lsn, change_lsn, event_serial_no);

INSERT INTO kdms.schema_version (version, description)
VALUES (2, '4단계 CDC: 테이블별 반영 위치, 동기화 진행 상태(docs/cdc.md)')
ON CONFLICT (version) DO NOTHING;
