package kdms.load;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kdms.load.Chunks.Chunk;

/**
 * 적재 상태(대상 PG kdms 스키마의 job·job_table·load_chunk·event_log). plan.md §4.2.
 * 행 값은 쓰지 않는다. 구간 경계값(PK 값)만 load_chunk 에 남는다.
 */
final class LoadState {

    /** kdms.job 한 행 */
    record Job(long id, String status, String configSha256) {
    }

    /** kdms.job_table 한 행 */
    record JobTable(long id, String srcSchema, String srcTable, String tgtSchema, String tgtTable, String status) {
    }

    /** kdms.load_chunk 한 행 */
    record ChunkRow(long id, Chunk chunk, String status, Long rowCount) {
    }

    private LoadState() {
    }

    static Job job(Connection c, String jobName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT job_id, status, config_sha256 FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Job(rs.getLong(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }

    /** "schema.table"(원천, 소문자) → 행 */
    static Map<String, JobTable> tables(Connection c, long jobId) throws SQLException {
        Map<String, JobTable> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT job_table_id, src_schema, src_table, tgt_schema, tgt_table, status
                FROM kdms.job_table WHERE job_id = ? ORDER BY job_table_id""")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobTable t = new JobTable(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
                    out.put(kdms.rules.Rules.tableKey(t.srcSchema(), t.srcTable()), t);
                }
            }
        }
        return out;
    }

    static void setJobStatus(Connection c, long jobId, String status, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kdms.job SET status = ?, last_error = ?, updated_at = now() WHERE job_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setLong(3, jobId);
            ps.executeUpdate();
        }
    }

    static void setConfigSha256(Connection c, long jobId, String sha) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kdms.job SET config_sha256 = ?, updated_at = now() WHERE job_id = ?")) {
            ps.setString(1, sha);
            ps.setLong(2, jobId);
            ps.executeUpdate();
        }
    }

    static void tableStarted(Connection c, long jobTableId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kdms.job_table SET status = 'LOADING', load_started_at = coalesce(load_started_at, now()), last_error = NULL
                WHERE job_table_id = ?""")) {
            ps.setLong(1, jobTableId);
            ps.executeUpdate();
        }
    }

    /** 구간이 모두 끝났으면 LOADED, 아니면 FAILED. 적재 행 수 = 끝난 구간 행 수 합 */
    static long tableFinished(Connection c, long jobTableId, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kdms.job_table SET status = ?, last_error = ?,
                       rows_loaded = (SELECT coalesce(sum(row_count), 0) FROM kdms.load_chunk WHERE job_table_id = ? AND status = 'DONE'),
                       load_finished_at = CASE WHEN ? IS NULL THEN now() END
                WHERE job_table_id = ?
                RETURNING rows_loaded""")) {
            ps.setString(1, error == null ? "LOADED" : "FAILED");
            ps.setString(2, error);
            ps.setLong(3, jobTableId);
            ps.setString(4, error);
            ps.setLong(5, jobTableId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static List<ChunkRow> chunks(Connection c, long jobTableId) throws SQLException {
        List<ChunkRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT chunk_id, chunk_no,
                       CASE WHEN lower_bound IS NULL THEN NULL ELSE ARRAY(SELECT jsonb_array_elements_text(lower_bound)) END,
                       CASE WHEN upper_bound IS NULL THEN NULL ELSE ARRAY(SELECT jsonb_array_elements_text(upper_bound)) END,
                       status, row_count
                FROM kdms.load_chunk WHERE job_table_id = ? ORDER BY chunk_no""")) {
            ps.setLong(1, jobTableId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(6);
                    out.add(new ChunkRow(rs.getLong(1), new Chunk(rs.getInt(2), list(rs.getArray(3)), list(rs.getArray(4))),
                            rs.getString(5), rs.wasNull() ? null : rows));
                }
            }
        }
        return out;
    }

    private static List<String> list(Array a) throws SQLException {
        return a == null ? null : Arrays.asList((String[]) a.getArray());
    }

    static void insertChunks(Connection c, long jobTableId, List<Chunk> chunks) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kdms.load_chunk (job_table_id, chunk_no, lower_bound, upper_bound)
                VALUES (?, ?, to_jsonb(?::text[]), to_jsonb(?::text[]))""")) {
            for (Chunk ch : chunks) {
                ps.setLong(1, jobTableId);
                ps.setInt(2, ch.no());
                ps.setArray(3, ch.lower() == null ? null : c.createArrayOf("text", ch.lower().toArray()));
                ps.setArray(4, ch.upper() == null ? null : c.createArrayOf("text", ch.upper().toArray()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void chunkRunning(Connection c, long chunkId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kdms.load_chunk SET status = 'RUNNING', attempts = attempts + 1, started_at = now(), finished_at = NULL, last_error = NULL
                WHERE chunk_id = ?""")) {
            ps.setLong(1, chunkId);
            ps.executeUpdate();
        }
    }

    /** 적재와 같은 트랜잭션에서 부른다: 구간 행이 커밋되면 DONE 도 함께 커밋된다(재시작 때 중복·누락 없음) */
    static void chunkDone(Connection c, long chunkId, long rows, long elapsedMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kdms.load_chunk SET status = 'DONE', row_count = ?, elapsed_ms = ?, finished_at = now(), last_error = NULL
                WHERE chunk_id = ?""")) {
            ps.setLong(1, rows);
            ps.setLong(2, elapsedMs);
            ps.setLong(3, chunkId);
            ps.executeUpdate();
        }
    }

    static void chunkFailed(Connection c, long chunkId, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kdms.load_chunk SET status = 'FAILED', finished_at = now(), last_error = ? WHERE chunk_id = ?")) {
            ps.setString(1, error);
            ps.setLong(2, chunkId);
            ps.executeUpdate();
        }
    }

    /** --reset: 구간·적재 기록을 지운다(대상 테이블 TRUNCATE 는 부른 쪽이 같은 트랜잭션에서) */
    static void resetTable(Connection c, long jobTableId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM kdms.load_chunk WHERE job_table_id = ?")) {
            ps.setLong(1, jobTableId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kdms.job_table SET status = 'PENDING', rows_loaded = NULL, load_started_at = NULL, load_finished_at = NULL, last_error = NULL
                WHERE job_table_id = ?""")) {
            ps.setLong(1, jobTableId);
            ps.executeUpdate();
        }
    }

    static void log(Connection c, long jobId, String level, String message) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kdms.event_log (job_id, level, stage, message) VALUES (?, ?, 'load', ?)")) {
            ps.setLong(1, jobId);
            ps.setString(2, level);
            ps.setString(3, message);
            ps.executeUpdate();
        }
    }
}
