package kdms.cutover;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.postgresql.util.PSQLException;

import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.Names;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.ForeignKeyPlan;
import kdms.ddl.SchemaPlan.IndexPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.SafeMessage;

/**
 * kdms check: 전환 뒤 대상이 앱을 받을 준비가 됐는지 점검한다(docs/runbook.md §7). 원천·대상 데이터는 바꾸지 않는다.
 * <ol>
 * <li>작업: 상태 DONE, 마지막 전환 DONE, 그 전환의 검증 불일치 0</li>
 * <li>다음 값: IDENTITY·SEQUENCE 의 대상 다음 값 = 원천 기준 값이고 대상 MAX 보다 크다(A09, T-L05·T-L06)</li>
 * <li>제약·인덱스: PK, 적재 뒤 UNIQUE·인덱스(유효), FK(검사 끝남) 가 계획대로 있다(T-C11)</li>
 * <li>계산 컬럼: GENERATED STORED 로 있다(B12, T-L13)</li>
 * <li>입력 시험(--probe): 대소문자만 다른 값(B14, T-L12)·없는 부모(T-C11) 입력이 막히는지 실제로 넣어 보고 모두 되돌린다.
 *     키는 새 값을 직접 넣으므로 시퀀스를 쓰지 않는다(nextval 은 ROLLBACK 해도 돌아오지 않는다)</li>
 * </ol>
 * 사람이 옮길 트리거·뷰·SP 는 경고로 다시 보여 준다(KIS:docs/appcompat.md).
 */
public final class AfterCutoverCheck {

    public enum Level {
        PASS("통과"), FAIL("실패"), WARN("경고"), SKIP("건너뜀");

        public final String label;

        Level(String label) {
            this.label = label;
        }
    }

    /** @param group 묶음 제목(출력 머리), what 무엇을, detail 결과 설명 */
    public record Item(String group, Level level, String what, String detail) {
    }

    public record Result(List<Item> items) {
        public long count(Level l) {
            return items.stream().filter(i -> i.level() == l).count();
        }

        public boolean ok() {
            return count(Level.FAIL) == 0;
        }
    }

    static final String G_JOB = "작업";
    static final String G_SEQ = "다음 값 IDENTITY·SEQUENCE (A09, T-L05·T-L06)";
    static final String G_DDL = "제약·인덱스 (T-C11)";
    static final String G_GEN = "계산 컬럼 (B12, T-L13)";
    static final String G_PROBE = "입력 시험 (모두 되돌림)";
    static final String G_MANUAL = "사람이 할 일 (자동 변환하지 않음, KIS:docs/appcompat.md)";

    private static final Pattern LOWER_COL = Pattern.compile("lower\\(\"((?:[^\"]|\"\")+)\"\\)");
    private static final Pattern FK = Pattern.compile(
            "FOREIGN KEY \\(\"((?:[^\"]|\"\")+)\"\\) REFERENCES (\"(?:[^\"]|\"\")+\"\\.\"(?:[^\"]|\"\")+\") \\(\"((?:[^\"]|\"\")+)\"\\)");
    private static final Pattern NUMERIC = Pattern.compile("^(smallint|integer|bigint|numeric|decimal)\\b.*");

    private final KdmsConfig cfg;
    private final SchemaPlan plan;
    private final Connections db;

    public AfterCutoverCheck(KdmsConfig cfg, SchemaPlan plan, Connections db) {
        this.cfg = cfg;
        this.plan = plan;
        this.db = db;
    }

    /**
     * @param probe 입력 시험까지(대상에 행을 넣어 보고 ROLLBACK)
     * @param each  점검 하나가 끝날 때마다(진행 출력)
     */
    public Result run(boolean probe, Consumer<Item> each) throws SQLException {
        List<Item> items = new ArrayList<>();
        Consumer<Item> add = i -> {
            items.add(i);
            each.accept(i);
        };
        try (Connection tgt = db.target()) {
            job(tgt, add);
            try (Connection src = db.source()) {
                for (SequenceSync.Check c : SequenceSync.check(plan, src, tgt)) {
                    add.accept(new Item(G_SEQ, c.ok() ? Level.PASS : Level.FAIL, c.target(), sequenceDetail(c)));
                }
            }
            for (TablePlan t : plan.tables()) {
                constraints(tgt, t, add);
            }
            for (TablePlan t : plan.tables()) {
                for (ColumnPlan c : t.columns()) {
                    if (c.generated() != null) {
                        add.accept(generated(tgt, t, c));
                    }
                }
            }
            if (probe) {
                probes(tgt, add);
            }
        }
        for (String m : CutoverRunner.manualWork(plan)) {
            add.accept(new Item(G_MANUAL, Level.WARN, m, null));
        }
        return new Result(List.copyOf(items));
    }

    // ---- 1. 작업·전환·검증 기록

    private void job(Connection tgt, Consumer<Item> add) throws SQLException {
        String status = null;
        Long jobId = null;
        boolean hasSchema;
        try (PreparedStatement ps = tgt.prepareStatement("SELECT to_regclass('kdms.cutover_run') IS NOT NULL");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            hasSchema = rs.getBoolean(1);
        }
        if (!hasSchema) {
            add.accept(new Item(G_JOB, Level.FAIL, "관리 스키마", "kdms.cutover_run 이 없다(kdms init·kdms cutover 전)"));
            return;
        }
        try (PreparedStatement ps = tgt.prepareStatement("SELECT job_id, status FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, cfg.jobName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    jobId = rs.getLong(1);
                    status = rs.getString(2);
                }
            }
        }
        if (jobId == null) {
            add.accept(new Item(G_JOB, Level.FAIL, "작업 " + cfg.jobName(), "없다(kdms schema 전)"));
            return;
        }
        add.accept(new Item(G_JOB, "DONE".equals(status) ? Level.PASS : Level.FAIL, "작업 " + cfg.jobName(),
                "상태 " + status + ("DONE".equals(status) ? "" : " (전환이 끝나지 않았다. kdms cutover --yes)")));
        try (PreparedStatement ps = tgt.prepareStatement("""
                SELECT c.cutover_id, c.status, c.elapsed_ms, v.run_id, v.checks, v.mismatches
                FROM kdms.cutover_run c LEFT JOIN kdms.verify_run v ON v.run_id = c.verify_run_id
                WHERE c.job_id = ? ORDER BY c.cutover_id DESC LIMIT 1""")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    add.accept(new Item(G_JOB, Level.FAIL, "전환 기록", "없다(kdms cutover 전)"));
                    return;
                }
                long id = rs.getLong(1);
                String cs = rs.getString(2);
                long ms = rs.getLong(3);
                add.accept(new Item(G_JOB, "DONE".equals(cs) ? Level.PASS : Level.FAIL, "마지막 전환 cutover " + id,
                        cs + ("DONE".equals(cs) ? String.format(Locale.ROOT, ", 소요 시간 %.1f초", ms / 1000.0) : "")));
                long runId = rs.getLong(4);
                if (rs.wasNull()) {
                    add.accept(new Item(G_JOB, Level.FAIL, "전환 검증", "cutover " + id + " 에 검증 기록이 없다"));
                } else {
                    int checks = rs.getInt(5);
                    int mismatches = rs.getInt(6);
                    add.accept(new Item(G_JOB, mismatches == 0 && checks > 0 ? Level.PASS : Level.FAIL, "전환 검증 run_id " + runId,
                            "검증 항목 " + checks + "개 중 일치 " + (checks - mismatches) + " · 불일치 " + mismatches));
                }
            }
        }
    }

    // ---- 2. 다음 값

    static String sequenceDetail(SequenceSync.Check c) {
        String src = c.srcValue() == null ? "원천 사용 안 함" : "원천 " + c.srcValue();
        String s = "대상 다음 값 " + c.actual() + (c.expected().equals(c.actual()) ? " = " : " ≠ 기대 " + c.expected() + " · ") + src
                + (c.srcValue() == null || !c.expected().equals(c.actual()) ? "" : " + " + c.inc())
                + (c.tgtMax() == null ? "" : " · 대상 " + (c.inc().signum() < 0 ? "MIN " : "MAX ") + c.tgtMax());
        if (!c.ok()) {
            s += c.expected().equals(c.actual()) ? " → 다음 입력이 기존 키와 겹친다" : " → 전환 뒤 원천에 쓰기가 있었거나 setval 이 빠졌다";
        }
        return s;
    }

    // ---- 3. 제약·인덱스

    private void constraints(Connection tgt, TablePlan t, Consumer<Item> add) throws SQLException {
        String table = t.tgtQualified();
        if (t.primaryKey() != null) {
            boolean pk;
            try (PreparedStatement ps = tgt.prepareStatement(
                    "SELECT count(*) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'p'")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    pk = rs.getInt(1) == 1;
                }
            }
            add.accept(new Item(G_DDL, pk ? Level.PASS : Level.FAIL, table + " PK",
                    pk ? "(" + String.join(", ", t.primaryKey().columns()) + ")" : "없다"));
        }
        for (IndexPlan ix : t.postLoad()) {
            Boolean valid = null;
            try (PreparedStatement ps = tgt.prepareStatement("""
                    SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE i.indrelid = ?::regclass AND c.relname = ?""")) {
                ps.setString(1, table);
                ps.setString(2, ix.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        valid = rs.getBoolean(1);
                    }
                }
            }
            add.accept(new Item(G_DDL, Boolean.TRUE.equals(valid) ? Level.PASS : Level.FAIL, table + " " + ix.name(),
                    valid == null ? "없다(kdms cutover 의 UNIQUE·인덱스 단계)" : valid ? (unique(ix) ? "유일 인덱스" : "인덱스") + " 유효"
                            : "INVALID(만들다 실패한 인덱스. DROP 뒤 kdms schema --phase post-load)"));
        }
        for (ForeignKeyPlan fk : t.foreignKeys()) {
            Boolean validated = null;
            try (PreparedStatement ps = tgt.prepareStatement(
                    "SELECT convalidated FROM pg_constraint WHERE conrelid = ?::regclass AND conname = ? AND contype = 'f'")) {
                ps.setString(1, table);
                ps.setString(2, fk.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        validated = rs.getBoolean(1);
                    }
                }
            }
            add.accept(new Item(G_DDL, Boolean.TRUE.equals(validated) ? Level.PASS : Level.FAIL, table + " " + fk.name(),
                    validated == null ? "FK 가 없다(kdms cutover 의 FK 단계)" : validated ? "FK 기존 행까지 검사됨" : "NOT VALID(기존 행 검사 안 됨)"));
        }
    }

    private static boolean unique(IndexPlan ix) {
        return ix.ddl().startsWith("CREATE UNIQUE") || ix.ddl().contains(" UNIQUE ");
    }

    // ---- 4. 계산 컬럼

    private static Item generated(Connection tgt, TablePlan t, ColumnPlan c) throws SQLException {
        String what = t.tgtQualified() + "." + Names.quote(c.tgtName());
        try (PreparedStatement ps = tgt.prepareStatement(
                "SELECT attgenerated FROM pg_attribute WHERE attrelid = ?::regclass AND attname = ? AND NOT attisdropped")) {
            ps.setString(1, t.tgtQualified());
            ps.setString(2, c.tgtName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Item(G_GEN, Level.FAIL, what, "컬럼이 없다");
                }
                boolean stored = "s".equals(rs.getString(1));
                return new Item(G_GEN, stored ? Level.PASS : Level.FAIL, what, stored ? "GENERATED ALWAYS AS (…) STORED" : "계산 컬럼이 아니다(값만 옮겨졌다)");
            }
        }
    }

    // ---- 5. 입력 시험(모두 되돌림)

    private void probes(Connection tgt, Consumer<Item> add) throws SQLException {
        boolean auto = tgt.getAutoCommit();
        tgt.setAutoCommit(false);
        try (Statement st = tgt.createStatement()) {
            // 운영 대상에서 남의 잠금을 오래 기다리지 않는다
            st.execute("SET LOCAL lock_timeout = '5s'");
            st.execute("SET LOCAL statement_timeout = '60s'");
            for (TablePlan t : plan.tables()) {
                for (IndexPlan ix : t.postLoad()) {
                    List<String> lower = lowerColumns(ix);
                    if (unique(ix) && !lower.isEmpty()) {
                        add.accept(ciProbe(tgt, t, ix, lower));
                    }
                }
                for (ForeignKeyPlan fk : t.foreignKeys()) {
                    add.accept(fkProbe(tgt, t, fk));
                }
            }
        } finally {
            tgt.rollback();
            tgt.setAutoCommit(auto);
        }
    }

    /** CREATE UNIQUE INDEX … (lower("a"), "b") 의 lower() 컬럼 */
    static List<String> lowerColumns(IndexPlan ix) {
        List<String> out = new ArrayList<>();
        Matcher m = LOWER_COL.matcher(ix.ddl());
        while (m.find()) {
            out.add(m.group(1).replace("\"\"", "\""));
        }
        return out;
    }

    /** B14: 있는 행 하나를 복사해 lower() 컬럼의 대소문자만 바꿔 넣는다 → 그 유일 인덱스 위반이어야 한다 */
    private Item ciProbe(Connection tgt, TablePlan t, IndexPlan ix, List<String> lower) throws SQLException {
        String what = t.tgtQualified() + " " + ix.name() + ": 대소문자만 다른 " + String.join("·", lower) + " 입력 (T-L12)";
        Set<String> changed = new LinkedHashSet<>(lower);
        String newKey = freshKey(t, changed);
        if (newKey != null && newKey.startsWith("!")) {
            return new Item(G_PROBE, Level.SKIP, what, newKey.substring(1));
        }
        StringBuilder where = new StringBuilder();
        for (String c : lower) {
            where.append(where.length() == 0 ? " WHERE " : " AND ").append(Names.quote(c)).append(" ~ '[A-Za-z]'");
        }
        String sql = copyRow(t, c -> lower.contains(c)
                ? "CASE WHEN " + Names.quote(c) + " = upper(" + Names.quote(c) + ") THEN lower(" + Names.quote(c) + ") ELSE upper(" + Names.quote(c) + ") END"
                : null, newKey, where.toString());
        return expectViolation(tgt, what, sql, "23505", ix.name(), "입력이 막혔다(유일 인덱스 " + ix.name() + ")");
    }

    /** FK: 있는 행 하나를 복사해 FK 컬럼을 부모에 없는 값으로 넣는다 → 그 FK 위반이어야 한다 */
    private Item fkProbe(Connection tgt, TablePlan t, ForeignKeyPlan fk) throws SQLException {
        Matcher m = FK.matcher(fk.ddl());
        String what = t.tgtQualified() + " " + fk.name() + ": 부모에 없는 값 입력 (T-C11)";
        if (!m.find()) {
            return new Item(G_PROBE, Level.SKIP, what, "여러 컬럼 FK 는 입력 시험을 하지 않는다");
        }
        String col = m.group(1).replace("\"\"", "\"");
        String parent = m.group(2);
        String parentCol = m.group(3).replace("\"\"", "\"");
        ColumnPlan c = column(t, col);
        if (c == null || !NUMERIC.matcher(c.tgtType().toLowerCase(Locale.ROOT)).matches()) {
            return new Item(G_PROBE, Level.SKIP, what, "숫자가 아닌 FK 컬럼은 입력 시험을 하지 않는다");
        }
        String newKey = freshKey(t, Set.of(col));
        if (newKey != null && newKey.startsWith("!")) {
            return new Item(G_PROBE, Level.SKIP, what, newKey.substring(1));
        }
        String missing = "(SELECT coalesce(min(" + Names.quote(parentCol) + "), 0) - 1000000 FROM " + parent + ")";
        String sql = copyRow(t, x -> x.equals(col) ? missing : null, newKey, "");
        return expectViolation(tgt, what, sql, "23503", fk.name(), "입력이 막혔다(FK " + fk.name() + ")");
    }

    /**
     * 복사한 행이 PK 에 걸리지 않게 바꿀 PK 컬럼. 바꾸는 컬럼에 PK 가 이미 들어 있으면(대소문자를 바꾼 PK 컬럼) null.
     * 숫자 PK 한 컬럼이면 그 컬럼 이름, 아니면 "!이유"
     */
    private static String freshKey(TablePlan t, Set<String> changed) {
        if (t.primaryKey() == null || t.primaryKey().columns().stream().anyMatch(changed::contains)) {
            return null;
        }
        List<String> pk = t.primaryKey().columns();
        if (pk.size() != 1) {
            return "!PK 가 여러 컬럼이라 새 키를 만들 수 없어 건너뛴다";
        }
        ColumnPlan c = column(t, pk.get(0));
        if (c == null || !NUMERIC.matcher(c.tgtType().toLowerCase(Locale.ROOT)).matches()) {
            return "!숫자가 아닌 PK 라 새 키를 만들 수 없어 건너뛴다";
        }
        return pk.get(0);
    }

    private static ColumnPlan column(TablePlan t, String tgtName) {
        return t.columns().stream().filter(c -> c.tgtName().equals(tgtName)).findFirst().orElse(null);
    }

    /**
     * INSERT INTO 표 (값 넣는 컬럼) [OVERRIDING SYSTEM VALUE] SELECT … FROM 표 [조건] LIMIT 1.
     * 키는 MAX + 1,000,000 을 직접 넣어 IDENTITY 시퀀스를 쓰지 않는다
     */
    private static String copyRow(TablePlan t, java.util.function.Function<String, String> override, String newKey, String where) {
        List<ColumnPlan> cols = t.loadColumns();
        boolean identity = cols.stream().anyMatch(c -> c.identity() != null);
        String names = cols.stream().map(c -> Names.quote(c.tgtName())).collect(Collectors.joining(", "));
        String values = cols.stream().map(c -> {
            String o = override.apply(c.tgtName());
            if (o != null) {
                return o;
            }
            if (c.tgtName().equals(newKey)) {
                return "(SELECT coalesce(max(" + Names.quote(newKey) + "), 0) + 1000000 FROM " + t.tgtQualified() + ")";
            }
            return Names.quote(c.tgtName());
        }).collect(Collectors.joining(", "));
        return "INSERT INTO " + t.tgtQualified() + " (" + names + ")" + (identity ? " OVERRIDING SYSTEM VALUE" : "")
                + " SELECT " + values + " FROM " + t.tgtQualified() + where + " LIMIT 1";
    }

    private static Item expectViolation(Connection tgt, String what, String sql, String state, String constraint, String passText)
            throws SQLException {
        try (Statement st = tgt.createStatement()) {
            st.execute("SAVEPOINT kdms_check");
            try {
                int n = st.executeUpdate(sql);
                st.execute("ROLLBACK TO SAVEPOINT kdms_check");
                return n == 0 ? new Item(G_PROBE, Level.SKIP, what, "복사할 행이 없다")
                        : new Item(G_PROBE, Level.FAIL, what, "막히지 않고 들어갔다(되돌림)");
            } catch (PSQLException e) {
                st.execute("ROLLBACK TO SAVEPOINT kdms_check");
                String got = e.getServerErrorMessage() == null ? null : e.getServerErrorMessage().getConstraint();
                if (state.equals(e.getSQLState()) && constraint.equals(got)) {
                    return new Item(G_PROBE, Level.PASS, what, passText);
                }
                return new Item(G_PROBE, Level.WARN, what, "다른 이유로 막혀 확인하지 못했다: " + SafeMessage.of(e));
            }
        }
    }
}
