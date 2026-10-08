package kdms.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 수집 쪽 상태(대상 PG kdms 스키마): change_log 저장, 워터마크 기록, Debezium 오프셋·이력 표. plan.md §4.2·§4.4, docs/cdc.md §3.
 */
public final class CaptureStore {

    /**
     * @param inserted   change_log 에 새로 넣은 변경 수
     * @param duplicates 이미 있거나 이미 반영한 위치라 버린 수(재시작 때 다시 받은 것)
     * @param watermark  이 배치에서 워터마크를 처음 기록했으면 그 LSN, 아니면 null
     */
    public record BatchResult(int inserted, int duplicates, String watermark) {
    }

    /** 워터마크 한 행(kdms.watermark) */
    public record Watermark(String startLsn, java.time.OffsetDateTime recordedAt) {
    }

    private CaptureStore() {
    }

    /**
     * 배치 하나를 한 트랜잭션으로 저장한다. 커밋한 뒤에 Debezium 오프셋을 커밋한다(부른 쪽).
     * 그래서 어디서 죽어도 커밋된 오프셋 이전의 변경은 모두 change_log 에 있거나 이미 반영됐다.
     *
     * @param streaming 스트리밍 중이라는 표시(하트비트·행 변경)가 이 배치에 있었으면 그 위치. 워터마크가 없으면 이 값으로 기록한다
     */
    public static BatchResult write(Connection c, long jobId, List<Captured.Record> changes, String streaming) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            String watermark = null;
            if (streaming != null) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO kdms.watermark (job_id, start_lsn) VALUES (?, ?)
                        ON CONFLICT (job_id) DO NOTHING RETURNING start_lsn""")) {
                    ps.setLong(1, jobId);
                    ps.setString(2, streaming);
                    try (ResultSet rs = ps.executeQuery()) {
                        watermark = rs.next() ? rs.getString(1) : null;
                    }
                }
            }
            int inserted = 0;
            if (!changes.isEmpty()) {
                // 테이블의 반영 위치 이하이면(이미 반영하고 지운 변경을 Debezium 이 다시 보냄) 넣지 않는다.
                // 반영 위치보다 뒤면 UNIQUE(위치)로 중복만 막는다
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO kdms.change_log (job_id, commit_lsn, change_lsn, event_serial_no, op, src_schema, src_table, payload, src_commit_at)
                        SELECT ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM kdms.job_table t
                            WHERE t.job_id = ? AND lower(t.src_schema) = lower(?) AND lower(t.src_table) = lower(?)
                              AND t.applied_commit_lsn IS NOT NULL
                              AND (t.applied_commit_lsn COLLATE "C", t.applied_change_lsn COLLATE "C", t.applied_event_serial_no)
                                  >= (? COLLATE "C", ? COLLATE "C", ?))
                        ON CONFLICT ON CONSTRAINT uq_change_log_pos DO NOTHING""")) {
                    for (Captured.Record r : changes) {
                        int i = 1;
                        ps.setLong(i++, jobId);
                        ps.setString(i++, r.position().commitLsn());
                        ps.setString(i++, r.position().changeLsn());
                        ps.setLong(i++, r.position().eventSerialNo());
                        ps.setString(i++, r.op());
                        ps.setString(i++, r.schema());
                        ps.setString(i++, r.table());
                        ps.setString(i++, r.payload().toString());
                        ps.setTimestamp(i++, r.srcCommitAt() == null ? null : Timestamp.from(r.srcCommitAt()));
                        ps.setLong(i++, jobId);
                        ps.setString(i++, r.schema());
                        ps.setString(i++, r.table());
                        ps.setString(i++, r.position().commitLsn());
                        ps.setString(i++, r.position().changeLsn());
                        ps.setLong(i++, r.position().eventSerialNo());
                        ps.addBatch();
                    }
                    for (int n : ps.executeBatch()) {
                        inserted += n > 0 ? 1 : 0;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE kdms.watermark SET changes_captured = changes_captured + ? WHERE job_id = ?")) {
                    ps.setLong(1, inserted);
                    ps.setLong(2, jobId);
                    ps.executeUpdate();
                }
            }
            c.commit();
            return new BatchResult(inserted, changes.size() - inserted, watermark);
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    public static Watermark watermark(Connection c, long jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT start_lsn, start_recorded_at FROM kdms.watermark WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Watermark(rs.getString(1), rs.getObject(2, java.time.OffsetDateTime.class)) : null;
            }
        }
    }

    /** 엔진이 표를 찾을 수 있게 미리 만든다(DebeziumProps 의 DDL 과 같은 모양) */
    public static void createDebeziumTables(Connection c, long jobId) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS kdms." + DebeziumProps.offsetTable(jobId) + " (id varchar(36) NOT NULL, offset_key text, "
                    + "offset_val text, record_insert_ts timestamp NOT NULL, record_insert_seq integer NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS kdms." + DebeziumProps.historyTable(jobId) + " (id varchar(36) NOT NULL, "
                    + "history_data text, history_data_seq integer, record_insert_ts timestamp NOT NULL, record_insert_seq integer NOT NULL, "
                    + "PRIMARY KEY (id, history_data_seq))");
        }
    }

    /** 저장된 Debezium 오프셋 행 수(0 이면 엔진이 처음부터 = 새 워터마크) */
    public static int offsetRows(Connection c, long jobId) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM kdms." + DebeziumProps.offsetTable(jobId))) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * 그 작업을 돌리는 kdms sync·load 가 없음을 확인한다(세션 advisory lock 을 잠깐 잡아 본다).
     * @return 실행 중인 명령 이름, 없으면 null
     */
    public static String running(Connection c, String jobName) throws SQLException {
        for (String what : new String[] {"sync", "load"}) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtext('kdms." + what + ":' || ?))")) {
                ps.setString(1, jobName);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        return "kdms " + what;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(hashtext('kdms." + what + ":' || ?))")) {
                ps.setString(1, jobName);
                ps.execute();
            }
        }
        return null;
    }

    /** kdms reset·schema --replace: 작업의 CDC 상태를 모두 지운다(워터마크·변경·반영 위치·Debezium 표) */
    public static void clear(Connection c, long jobId) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS kdms." + DebeziumProps.offsetTable(jobId));
            st.execute("DROP TABLE IF EXISTS kdms." + DebeziumProps.historyTable(jobId));
        }
        for (String sql : new String[] {
                "DELETE FROM kdms.change_log WHERE job_id = ?",
                "DELETE FROM kdms.watermark WHERE job_id = ?",
                """
                UPDATE kdms.job_table SET applied_commit_lsn = NULL, applied_change_lsn = NULL, applied_event_serial_no = NULL,
                       changes_applied = 0 WHERE job_id = ?"""}) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, jobId);
                ps.executeUpdate();
            }
        }
    }
}
