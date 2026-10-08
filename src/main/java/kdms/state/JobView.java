package kdms.state;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 작업 하나의 현재 상태(대상 PG kdms 스키마에서 읽기만). kdms status 와 웹 화면이 같은 값을 보여 준다.
 * 행 값은 들어 있지 않다(건수·위치·시각·검증 숫자만, plan.md R8).
 *
 * @param job      작업(없으면 null, 나머지도 비어 있음)
 * @param tables   테이블별 적재·반영
 * @param sync     변경분 동기화(워터마크가 없으면 null)
 * @param verify   마지막 검증(없으면 null)
 * @param cutover  마지막 전환(없으면 null)
 * @param events   최근 기록(새것부터)
 */
public record JobView(Job job, List<Table> tables, Sync sync, Verify verify, Cutover cutover, List<Event> events) {

    /** 작업 상태 순서(화면의 단계 표시) */
    public static final List<String> FLOW = List.of("PLANNED", "SCHEMA_DONE", "LOADING", "SYNCING", "CUTOVER", "VERIFIED", "DONE");

    public record Job(long id, String name, String status, OffsetDateTime updatedAt, String lastError) {
    }

    /**
     * @param chunksDone   끝난 적재 구간 수
     * @param chunks       적재 구간 수(아직 나누지 않았으면 0)
     * @param pending      반영 대기 변경 수(change_log)
     */
    public record Table(String source, String target, boolean hasPk, String status, Long rowsEstimate, Long rowsLoaded,
                        int chunksDone, int chunks, long changesApplied, long pending, String lastError) {
    }

    /**
     * @param statusAt 마지막으로 kdms sync 가 진행 상태를 쓴 시각. 오래됐으면 sync 가 돌지 않는 것
     * @param srcMaxAt 원천 마지막 커밋 시각(원천 시계)
     */
    public record Sync(String startLsn, OffsetDateTime startRecordedAt, long captured, long applied, Long pending,
                      BigDecimal lagSeconds, LocalDateTime srcMaxAt, OffsetDateTime statusAt, OffsetDateTime appliedAt) {
    }

    /** @param tables 테이블별 일치 여부(source → 일치) */
    public record Verify(long runId, OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer checks, Integer mismatches,
                         List<VerifyTable> tables) {
    }

    public record VerifyTable(String source, int checks, int mismatches, long rowDiffs) {
    }

    public record Cutover(long id, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt, Long elapsedMs,
                          Long verifyRunId, String lastError, List<Step> steps) {
    }

    public record Step(int no, String step, String status, Long elapsedMs, String detail) {
    }

    public record Event(OffsetDateTime at, String level, String stage, String message) {
    }

    /** 관리 스키마가 없거나 작업이 없으면 job = null */
    public static JobView read(Connection c, String jobName, int events) throws SQLException {
        SchemaInstaller.Installed v = SchemaInstaller.installedVersion(c);
        if (v == null) {
            return new JobView(null, List.of(), null, null, null, List.of());
        }
        Job job = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT job_id, job_name, status, updated_at, last_error FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    job = new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class), rs.getString(5));
                }
            }
        }
        if (job == null) {
            return new JobView(null, List.of(), null, null, null, List.of());
        }
        boolean v2 = v.version() >= 2;
        boolean v3 = v.version() >= 3;
        return new JobView(job, tables(c, job.id(), v2), v2 ? sync(c, job.id()) : null, verify(c, job.id()),
                v3 ? cutover(c, job.id()) : null, events(c, job.id(), events));
    }

    private static List<Table> tables(Connection c, long jobId, boolean v2) throws SQLException {
        List<Table> out = new ArrayList<>();
        String applied = v2 ? "t.changes_applied" : "0";
        String pending = v2 ? """
                (SELECT count(*) FROM kdms.change_log l WHERE l.job_id = t.job_id
                   AND lower(l.src_schema) = lower(t.src_schema) AND lower(l.src_table) = lower(t.src_table))""" : "0";
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.src_schema || '.' || t.src_table, t.tgt_schema || '.' || t.tgt_table, t.has_pk, t.status,
                       t.rows_estimate, t.rows_loaded,
                       (SELECT count(*) FILTER (WHERE k.status = 'DONE') FROM kdms.load_chunk k WHERE k.job_table_id = t.job_table_id),
                       (SELECT count(*) FROM kdms.load_chunk k WHERE k.job_table_id = t.job_table_id),
                       %s, %s, t.last_error
                FROM kdms.job_table t WHERE t.job_id = ? ORDER BY t.job_table_id""".formatted(applied, pending))) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Table(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getObject(5, Long.class),
                            rs.getObject(6, Long.class), rs.getInt(7), rs.getInt(8), rs.getLong(9), rs.getLong(10), rs.getString(11)));
                }
            }
        }
        return out;
    }

    private static Sync sync(Connection c, long jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT start_lsn, start_recorded_at, changes_captured, changes_applied, pending_changes, lag_seconds,
                       src_max_lsn_at, sync_status_at, applied_at
                FROM kdms.watermark WHERE job_id = ?""")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Sync(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getLong(3), rs.getLong(4),
                        rs.getObject(5, Long.class), rs.getBigDecimal(6), rs.getObject(7, LocalDateTime.class),
                        rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class));
            }
        }
    }

    private static Verify verify(Connection c, long jobId) throws SQLException {
        long runId;
        Verify v;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT run_id, started_at, finished_at, checks, mismatches FROM kdms.verify_run
                WHERE job_id = ? ORDER BY run_id DESC LIMIT 1""")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                runId = rs.getLong(1);
                v = new Verify(runId, rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                        rs.getObject(4, Integer.class), rs.getObject(5, Integer.class), List.of());
            }
        }
        List<VerifyTable> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.src_schema || '.' || t.src_table, count(r.verify_id), count(r.verify_id) FILTER (WHERE NOT r.matched),
                       (SELECT count(*) FROM kdms.verify_row_diff d WHERE d.run_id = ? AND d.job_table_id = t.job_table_id)
                FROM kdms.job_table t JOIN kdms.verify_result r ON r.job_table_id = t.job_table_id AND r.run_id = ?
                GROUP BY t.job_table_id, t.src_schema, t.src_table ORDER BY t.job_table_id""")) {
            ps.setLong(1, runId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new VerifyTable(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
                }
            }
        }
        return new Verify(v.runId(), v.startedAt(), v.finishedAt(), v.checks(), v.mismatches(), List.copyOf(tables));
    }

    private static Cutover cutover(Connection c, long jobId) throws SQLException {
        Cutover head;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT cutover_id, status, started_at, finished_at, elapsed_ms, verify_run_id, last_error FROM kdms.cutover_run
                WHERE job_id = ? ORDER BY cutover_id DESC LIMIT 1""")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                head = new Cutover(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(5, Long.class), rs.getObject(6, Long.class), rs.getString(7), List.of());
            }
        }
        List<Step> steps = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT step_no, step, status, elapsed_ms, detail FROM kdms.cutover_step WHERE cutover_id = ? ORDER BY step_no")) {
            ps.setLong(1, head.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    steps.add(new Step(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getObject(4, Long.class), rs.getString(5)));
                }
            }
        }
        return new Cutover(head.id(), head.status(), head.startedAt(), head.finishedAt(), head.elapsedMs(), head.verifyRunId(),
                head.lastError(), List.copyOf(steps));
    }

    private static List<Event> events(Connection c, long jobId, int limit) throws SQLException {
        List<Event> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT logged_at, level, stage, message FROM kdms.event_log WHERE job_id = ? ORDER BY event_id DESC LIMIT ?""")) {
            ps.setLong(1, jobId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }
}
