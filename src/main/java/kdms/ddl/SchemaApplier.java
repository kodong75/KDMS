package kdms.ddl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import kdms.ddl.DdlWriter.Phase;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.state.SchemaInstaller;

/**
 * 계획한 DDL 을 대상 PG 에 한 트랜잭션으로 적용하고, 적재 전 단계면 작업(kdms.job)·테이블(kdms.job_table)을 등록한다.
 * 중간에 실패하면 아무것도 남지 않는다(PG 는 DDL 도 트랜잭션).
 */
public final class SchemaApplier {

    /** 이 상태부터는 테이블을 다시 만들면 적재·반영한 데이터가 사라진다 */
    static final Set<String> STARTED = Set.of("LOADING", "SYNCING", "CUTOVER", "VERIFIED", "DONE");

    /**
     * @param jobName    작업 이름(kdms.job.job_name)
     * @param srcServer  host:port (비밀번호 없음)
     * @param srcDatabase 원천 DB
     * @param tgtDatabase 대상 DB
     * @param configSha256 설정+규칙 파일 해시
     */
    public record Job(String jobName, String srcServer, String srcDatabase, String tgtDatabase, String configSha256) {
    }

    /**
     * @param dropped --replace 로 지운 대상 테이블·시퀀스
     * @param skipped 적재 뒤·전환 단계에서 이미 있어 건너뛴 인덱스·제약 수(다시 실행해도 안전하게)
     */
    public record Result(long jobId, int statements, List<String> dropped, int skipped) {
    }

    /** 사람이 고칠 수 있는 이유로 적용하지 않음(이미 있는 테이블 등) */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private SchemaApplier() {
    }

    public static Result apply(Connection c, SchemaPlan plan, Phase phase, boolean replace, Job job) throws SQLException {
        if (plan.blocked()) {
            throw new Refused("계획에 오류가 있어 적용하지 않는다(kdms plan 보고서의 오류 절)");
        }
        SchemaInstaller.install(c);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('kdms.job:' || ?))")) {
                ps.setString(1, job.jobName());
                ps.execute();
            }
            String status = jobStatus(c, job.jobName());
            String running = phase == Phase.PRE_LOAD ? kdms.cdc.CaptureStore.running(c, job.jobName()) : null;
            if (running != null) {
                throw new Refused(running + " 이 작업 " + job.jobName() + " 을 실행하고 있다. 멈춘 뒤 다시 한다");
            }
            if (phase == Phase.PRE_LOAD && status != null && STARTED.contains(status)) {
                throw new Refused("작업 " + job.jobName() + " 은 이미 " + status + " 단계다. 테이블을 다시 만들지 않는다");
            }

            List<String> dropped = new ArrayList<>();
            if (phase == Phase.PRE_LOAD) {
                List<String> existing = existing(c, plan);
                if (!existing.isEmpty() && !replace) {
                    throw new Refused("대상에 이미 있다: " + String.join(", ", existing)
                            + "\n지우고 다시 만들려면 --replace (그 안의 데이터도 지워진다)");
                }
                for (TablePlan t : plan.tables()) {
                    if (existing.contains(t.tgtQualified())) {
                        st.execute("DROP TABLE " + t.tgtQualified() + " CASCADE");
                        dropped.add(t.tgtQualified());
                    }
                }
                for (SchemaPlan.SequencePlan s : plan.sequences()) {
                    String q = Names.quote(s.tgtSchema()) + "." + Names.quote(s.tgtName());
                    if (existing.contains(q)) {
                        st.execute("DROP SEQUENCE " + q + " CASCADE");
                        dropped.add(q);
                    }
                }
            }

            List<String> stmts = new ArrayList<>();
            int skipped = 0;
            for (String sql : DdlWriter.statements(plan, phase)) {
                if (phase != Phase.PRE_LOAD && alreadyThere(c, sql)) {
                    skipped++;
                } else {
                    stmts.add(sql);
                }
            }
            for (String sql : stmts) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException(e.getMessage() + "\n실패한 문장:\n" + sql, e.getSQLState(), e);
                }
            }

            long jobId = phase == Phase.PRE_LOAD ? registerJob(c, plan, job) : existingJobId(c, job.jobName());
            if (jobId > 0) {
                log(c, jobId, "schema " + phase.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + " 적용: 문장 " + stmts.size()
                        + "개, 테이블 " + plan.tables().size() + "개" + (dropped.isEmpty() ? "" : ", 다시 만든 것 " + dropped.size() + "개")
                        + (skipped > 0 ? ", 이미 있어 건너뜀 " + skipped + "개" : ""));
            }
            c.commit();
            return new Result(jobId, stmts.size(), List.copyOf(dropped), skipped);
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static final java.util.regex.Pattern CREATE_INDEX = java.util.regex.Pattern.compile(
            "^CREATE (?:UNIQUE )?INDEX (\"(?:[^\"]|\"\")*\") ON (\"(?:[^\"]|\"\")*\")\\.");
    private static final java.util.regex.Pattern ADD_CONSTRAINT = java.util.regex.Pattern.compile(
            "^ALTER TABLE (\"(?:[^\"]|\"\")*\"\\.\"(?:[^\"]|\"\")*\") ADD CONSTRAINT (\"(?:[^\"]|\"\")*\")");

    /** 적재 뒤·전환 DDL 이 만드는 인덱스·제약이 이미 있나(kdms load 가 끝에 적용하고 사람이 다시 실행해도 안전하게) */
    static boolean alreadyThere(Connection c, String sql) throws SQLException {
        java.util.regex.Matcher m = CREATE_INDEX.matcher(sql);
        if (m.find()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
                ps.setString(1, m.group(2) + "." + m.group(1));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        }
        m = ADD_CONSTRAINT.matcher(sql);
        if (m.find()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = to_regclass(?) AND conname = ?)")) {
                ps.setString(1, m.group(1));
                String q = m.group(2);
                ps.setString(2, q.substring(1, q.length() - 1).replace("\"\"", "\""));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        }
        return false;
    }

    /** 대상에 이미 있는 테이블·시퀀스("스키마"."이름") */
    static List<String> existing(Connection c, SchemaPlan plan) throws SQLException {
        List<String> names = new ArrayList<>();
        plan.tables().forEach(t -> names.add(t.tgtQualified()));
        plan.sequences().forEach(s -> names.add(Names.quote(s.tgtSchema()) + "." + Names.quote(s.tgtName())));
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            for (String n : names) {
                ps.setString(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    static String jobStatus(Connection c, String jobName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static long existingJobId(Connection c, String jobName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT job_id FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static long registerJob(Connection c, SchemaPlan plan, Job job) throws SQLException {
        long jobId;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kdms.job (job_name, src_server, src_database, tgt_database, status, config_sha256)
                VALUES (?, ?, ?, ?, 'SCHEMA_DONE', ?)
                ON CONFLICT (job_name) DO UPDATE
                   SET src_server = EXCLUDED.src_server, src_database = EXCLUDED.src_database,
                       tgt_database = EXCLUDED.tgt_database, status = 'SCHEMA_DONE',
                       config_sha256 = EXCLUDED.config_sha256, updated_at = now(), last_error = NULL
                RETURNING job_id""")) {
            ps.setString(1, job.jobName());
            ps.setString(2, job.srcServer());
            ps.setString(3, job.srcDatabase());
            ps.setString(4, job.tgtDatabase());
            ps.setString(5, job.configSha256());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                jobId = rs.getLong(1);
            }
        }
        // 같은 job_id 를 다시 쓰므로 이전 워터마크·변경·Debezium 오프셋도 지운다(테이블을 새로 만들면 그 위치는 의미가 없다)
        kdms.cdc.CaptureStore.clear(c, jobId);
        // 적재 전이므로 테이블 목록을 새로 쓴다(load_chunk 도 함께 지워진다)
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM kdms.job_table WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kdms.job_table (job_id, src_schema, src_table, tgt_schema, tgt_table, has_pk, rows_estimate)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            for (TablePlan t : plan.tables()) {
                ps.setLong(1, jobId);
                ps.setString(2, t.srcSchema());
                ps.setString(3, t.srcName());
                ps.setString(4, t.tgtSchema());
                ps.setString(5, t.tgtName());
                ps.setBoolean(6, t.primaryKey() != null);
                ps.setLong(7, t.rowsEstimate());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return jobId;
    }

    private static void log(Connection c, long jobId, String message) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kdms.event_log (job_id, level, stage, message) VALUES (?, 'INFO', 'schema', ?)")) {
            ps.setLong(1, jobId);
            ps.setString(2, message);
            ps.executeUpdate();
        }
    }
}
