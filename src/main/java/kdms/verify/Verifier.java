package kdms.verify;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.Chunks;
import kdms.load.SafeMessage;
import kdms.rules.Rules;
import kdms.state.SchemaInstaller;

/**
 * 건수·합계·해시 검증(plan.md §4.7). 원천·대상 각각에서 계산하고 숫자만 가져와 비교한다(행 값은 가져오지 않는다).
 * 해시나 건수가 다르면 PK 정규화 문자열의 MD5 로 묶음(0~65535)을 나눠 묶음별 합을 비교하고, 다른 묶음만 PK 별 해시를 가져와
 * 어느 행인지 찾는다(두 DB 의 정렬 순서가 달라도 된다). 결과는 kdms.verify_run·verify_result·verify_row_diff 에 남긴다(PK 만).
 */
public final class Verifier {

    /** 테이블마다 verify_row_diff 에 남기는 차이 행 수 상한 */
    public static final int MAX_ROW_DIFFS = 100;
    /** 다른 묶음이 이보다 많으면 IN 목록 대신 테이블을 한 번 훑어 앱에서 묶음을 거른다(긴 IN 목록은 원천 컴파일이 느리다) */
    static final int MAX_IN_BUCKETS = 200;

    /** @param kind count | sum | hash, target sum 이면 컬럼(대상 이름) */
    public record Check(String kind, String target, BigDecimal source, BigDecimal tgt) {
        public boolean matched() {
            return source == null ? tgt == null : tgt != null && source.compareTo(tgt) == 0;
        }
    }

    /** @param side missing(원천에만) | extra(대상에만) | diff(값 다름) */
    public record RowDiff(List<String> pk, String side) {
    }

    /**
     * @param diffTotal 찾은 차이 행 수(rowDiffs 는 앞 {@link #MAX_ROW_DIFFS}개)
     * @param notes     해시 정규화 주의(xml 등), PK 없어 행 비교 생략 등
     */
    public record TableResult(TablePlan table, List<Check> checks, List<RowDiff> rowDiffs, long diffTotal,
                              Map<String, Long> diffSides, List<String> notes, long elapsedMs, String error) {
        public boolean matched() {
            return error == null && checks.stream().allMatch(Check::matched);
        }
    }

    public record Result(long runId, List<TableResult> tables) {
        public long checks() {
            return tables.stream().mapToLong(t -> t.checks().size()).sum();
        }

        public long mismatches() {
            return tables.stream().mapToLong(t -> t.checks().stream().filter(c -> !c.matched()).count() + (t.error() == null ? 0 : 1)).sum();
        }
    }

    /** 사람이 고칠 수 있는 이유로 검증하지 않음 */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private final KdmsConfig cfg;
    private final Rules rules;
    private final SchemaPlan plan;
    private final Connections db;

    public Verifier(KdmsConfig cfg, Rules rules, SchemaPlan plan, Connections db) {
        this.cfg = cfg;
        this.rules = rules;
        this.plan = plan;
        this.db = db;
    }

    /** @param tables 원천 "schema.table"(소문자). 비우면 전부 */
    public Result run(Set<String> tables, java.util.function.Consumer<TableResult> progress) throws SQLException {
        try (Connection state = db.target()) {
            SchemaInstaller.install(state);
            long jobId;
            Map<String, Long> jobTables = new HashMap<>();
            try (PreparedStatement ps = state.prepareStatement("""
                    SELECT j.job_id, t.src_schema, t.src_table, t.job_table_id
                    FROM kdms.job j LEFT JOIN kdms.job_table t ON t.job_id = j.job_id WHERE j.job_name = ?""")) {
                ps.setString(1, cfg.jobName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new Refused("작업 " + cfg.jobName() + " 이 없다. kdms schema·kdms load 를 먼저 한다");
                    }
                    jobId = rs.getLong(1);
                    do {
                        if (rs.getString(2) != null) {
                            jobTables.put(Rules.tableKey(rs.getString(2), rs.getString(3)), rs.getLong(4));
                        }
                    } while (rs.next());
                }
            }
            List<TablePlan> selected = plan.tables().stream()
                    .filter(t -> tables == null || tables.isEmpty() || tables.contains(Rules.tableKey(t.srcSchema(), t.srcName())))
                    .toList();
            if (tables != null) {
                for (String n : tables) {
                    if (selected.stream().noneMatch(t -> Rules.tableKey(t.srcSchema(), t.srcName()).equals(n))) {
                        throw new Refused("계획에 없는 테이블: " + n + " (원천 이름 schema.table)");
                    }
                }
            }
            for (TablePlan t : selected) {
                if (!jobTables.containsKey(Rules.tableKey(t.srcSchema(), t.srcName()))) {
                    throw new Refused(t.srcQualified() + " 이 작업에 등록되어 있지 않다. kdms schema 를 다시 한다");
                }
            }

            long runId;
            try (PreparedStatement ps = state.prepareStatement("INSERT INTO kdms.verify_run (job_id) VALUES (?) RETURNING run_id")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    runId = rs.getLong(1);
                }
            }
            List<TableResult> results = new ArrayList<>();
            for (TablePlan t : selected) {
                TableResult r = table(t);
                save(state, runId, jobTables.get(Rules.tableKey(t.srcSchema(), t.srcName())), r);
                results.add(r);
                if (progress != null) {
                    progress.accept(r);
                }
            }
            Result result = new Result(runId, results);
            try (PreparedStatement ps = state.prepareStatement(
                    "UPDATE kdms.verify_run SET finished_at = now(), checks = ?, mismatches = ? WHERE run_id = ?")) {
                ps.setLong(1, result.checks());
                ps.setLong(2, result.mismatches());
                ps.setLong(3, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = state.prepareStatement(
                    "INSERT INTO kdms.event_log (job_id, level, stage, message) VALUES (?, ?, 'verify', ?)")) {
                ps.setLong(1, jobId);
                ps.setString(2, result.mismatches() == 0 ? "INFO" : "WARN");
                ps.setString(3, "verify run " + runId + ": 테이블 " + results.size() + "개, 항목 " + result.checks() + "개, 불일치 " + result.mismatches());
                ps.executeUpdate();
            }
            return result;
        }
    }

    /** 테이블 하나 검증 */
    TableResult table(TablePlan t) {
        long started = System.nanoTime();
        List<String> notes = new ArrayList<>();
        try {
            List<Normalizer.Expr> exprs = t.columns().stream()
                    .map(c -> Normalizer.column(c, rules.sentinelValues(), rules.text().nulReplacement())).toList();
            exprs.stream().filter(e -> e.note() != null).forEach(e -> notes.add(e.column().srcName() + ": " + e.note()));
            List<ColumnPlan> sums = t.columns().stream().filter(Normalizer::summed).toList();
            String src = sourceSummarySql(t, exprs, sums);
            String tgt = targetSummarySql(t, exprs, sums);
            List<BigDecimal[]> both = parallel(() -> summary(db.source(), src, sums.size()), () -> summary(db.target(), tgt, sums.size()));
            BigDecimal[] s = both.get(0);
            BigDecimal[] g = both.get(1);
            List<Check> checks = new ArrayList<>();
            checks.add(new Check("count", "", s[0], g[0]));
            checks.add(new Check("hash", "", s[1], g[1]));
            for (int i = 0; i < sums.size(); i++) {
                checks.add(new Check("sum", sums.get(i).tgtName(), s[2 + i], g[2 + i]));
            }
            List<RowDiff> diffs = List.of();
            long diffTotal = 0;
            Map<String, Long> sides = Map.of();
            boolean rowsDiffer = !checks.get(0).matched() || !checks.get(1).matched();
            if (rowsDiffer) {
                if (t.primaryKey() == null) {
                    notes.add("PK 가 없어 행 단위 차이는 찾지 않는다(plan.md §4.6)");
                } else {
                    List<RowDiff> all = rowDiffs(t, exprs);
                    diffTotal = all.size();
                    sides = all.stream().collect(Collectors.groupingBy(RowDiff::side, LinkedHashMap::new, Collectors.counting()));
                    diffs = all.subList(0, Math.min(all.size(), MAX_ROW_DIFFS));
                }
            }
            return new TableResult(t, checks, diffs, diffTotal, sides, notes, (System.nanoTime() - started) / 1_000_000, null);
        } catch (SQLException | RuntimeException e) {
            return new TableResult(t, List.of(), List.of(), 0, Map.of(), notes, (System.nanoTime() - started) / 1_000_000, SafeMessage.of(e));
        }
    }

    static String sourceSummarySql(TablePlan t, List<Normalizer.Expr> exprs, List<ColumnPlan> sums) {
        StringBuilder b = new StringBuilder("SELECT COUNT_BIG(*), SUM(")
                .append(Normalizer.sourceHash(Normalizer.sourceRow(exprs))).append(')');
        sums.forEach(c -> b.append(",\n       ").append(Normalizer.sourceSum(c)));
        return b.append("\nFROM ").append(source(t)).toString();
    }

    static String targetSummarySql(TablePlan t, List<Normalizer.Expr> exprs, List<ColumnPlan> sums) {
        StringBuilder b = new StringBuilder("SELECT count(*), sum(")
                .append(Normalizer.targetHash(Normalizer.targetRow(exprs))).append(')');
        sums.forEach(c -> b.append(",\n       ").append(Normalizer.targetSum(c)));
        return b.append("\nFROM ").append(t.tgtQualified()).toString();
    }

    private static String source(TablePlan t) {
        return kdms.catalog.SourceDataScanner.bracket(t.srcSchema()) + "." + kdms.catalog.SourceDataScanner.bracket(t.srcName());
    }

    private static BigDecimal[] summary(Connection c, String sql, int sums) throws SQLException {
        try (c; Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            BigDecimal[] out = new BigDecimal[2 + sums];
            for (int i = 0; i < out.length; i++) {
                out[i] = rs.getBigDecimal(i + 1);
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- 행 단위 차이

    List<RowDiff> rowDiffs(TablePlan t, List<Normalizer.Expr> exprs) throws SQLException {
        List<ColumnPlan> keys = Chunks.keyColumns(t);
        List<Normalizer.Expr> pk = keys.stream().map(k -> exprs.stream().filter(e -> e.column() == k).findFirst().orElseThrow()).toList();
        String sPk = pk.stream().map(e -> "COALESCE(" + e.source() + ", N'\\N')").collect(Collectors.joining(" + N'|' + "));
        String gPk = pk.stream().map(e -> "coalesce(" + e.target() + ", '\\N')").collect(Collectors.joining(" || '|' || "));
        String sHash = Normalizer.sourceHash(Normalizer.sourceRow(exprs));
        String gHash = Normalizer.targetHash(Normalizer.targetRow(exprs));
        String sBucket = Normalizer.sourceBucket(sPk);
        String gBucket = Normalizer.targetBucket(gPk);

        String sAgg = "SELECT b, COUNT_BIG(*), SUM(h) FROM (SELECT " + sBucket + " AS b, " + sHash + " AS h FROM " + source(t) + ") x GROUP BY b";
        String gAgg = "SELECT b, count(*), sum(h) FROM (SELECT " + gBucket + " AS b, " + gHash + " AS h FROM " + t.tgtQualified() + ") x GROUP BY b";
        List<Map<Integer, String>> agg = parallel(() -> buckets(db.source(), sAgg), () -> buckets(db.target(), gAgg));
        Set<Integer> differ = new TreeSet<>();
        Set<Integer> all = new TreeSet<>(agg.get(0).keySet());
        all.addAll(agg.get(1).keySet());
        for (Integer b : all) {
            if (!Objects.equals(agg.get(0).get(b), agg.get(1).get(b))) {
                differ.add(b);
            }
        }
        List<RowDiff> out = new ArrayList<>();
        if (differ.isEmpty()) {
            return out;
        }
        String sRows = "SELECT " + pk.stream().map(Normalizer.Expr::source).collect(Collectors.joining(", ")) + ", " + sHash + ", " + sBucket
                + " FROM " + source(t);
        String gRows = "SELECT " + pk.stream().map(Normalizer.Expr::target).collect(Collectors.joining(", ")) + ", " + gHash + ", " + gBucket
                + " FROM " + t.tgtQualified();
        if (differ.size() <= MAX_IN_BUCKETS) {
            String in = differ.stream().map(String::valueOf).collect(Collectors.joining(", "));
            sRows += " WHERE " + sBucket + " IN (" + in + ")";
            gRows += " WHERE " + gBucket + " IN (" + in + ")";
        }
        String sq = sRows;
        String gq = gRows;
        List<Map<List<String>, BigDecimal>> rows = parallel(() -> rows(db.source(), sq, pk.size(), differ),
                () -> rows(db.target(), gq, pk.size(), differ));
        Map<List<String>, BigDecimal> s = rows.get(0);
        Map<List<String>, BigDecimal> g = rows.get(1);
        for (Map.Entry<List<String>, BigDecimal> e : s.entrySet()) {
            BigDecimal other = g.get(e.getKey());
            if (other == null) {
                out.add(new RowDiff(e.getKey(), "missing"));
            } else if (other.compareTo(e.getValue()) != 0) {
                out.add(new RowDiff(e.getKey(), "diff"));
            }
        }
        for (List<String> k : g.keySet()) {
            if (!s.containsKey(k)) {
                out.add(new RowDiff(k, "extra"));
            }
        }
        out.sort((a, b) -> String.join("|", a.pk()).compareTo(String.join("|", b.pk())));
        return out;
    }

    private static Map<Integer, String> buckets(Connection c, String sql) throws SQLException {
        Map<Integer, String> out = new HashMap<>();
        try (c; Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                BigDecimal h = rs.getBigDecimal(3);
                out.put(rs.getInt(1), rs.getLong(2) + ":" + (h == null ? "" : h.toBigInteger()));
            }
        }
        return out;
    }

    /** PK 정규화 값 → 행 해시. 다른 묶음의 행만 남긴다 */
    private static Map<List<String>, BigDecimal> rows(Connection c, String sql, int keys, Set<Integer> buckets) throws SQLException {
        Map<List<String>, BigDecimal> out = new LinkedHashMap<>();
        try (c; Statement st = c.createStatement()) {
            st.setFetchSize(10_000);
            ResultSet rs = st.executeQuery(sql);
            while (rs.next()) {
                if (!buckets.contains(rs.getInt(keys + 2))) {
                    continue;
                }
                List<String> k = new ArrayList<>(keys);
                for (int i = 1; i <= keys; i++) {
                    k.add(rs.getString(i));
                }
                out.put(List.copyOf(k), rs.getBigDecimal(keys + 1));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 저장

    private static void save(Connection c, long runId, long jobTableId, TableResult r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kdms.verify_result (run_id, job_table_id, check_kind, check_target, src_value, tgt_value, matched)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            for (Check ch : r.checks()) {
                ps.setLong(1, runId);
                ps.setLong(2, jobTableId);
                ps.setString(3, ch.kind());
                ps.setString(4, ch.target());
                ps.setString(5, ch.source() == null ? null : ch.source().toPlainString());
                ps.setString(6, ch.tgt() == null ? null : ch.tgt().toPlainString());
                ps.setBoolean(7, ch.matched());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kdms.verify_row_diff (run_id, job_table_id, pk, side) VALUES (?, ?, to_jsonb(?::text[]), ?)")) {
            for (RowDiff d : r.rowDiffs()) {
                ps.setLong(1, runId);
                ps.setLong(2, jobTableId);
                ps.setArray(3, c.createArrayOf("text", d.pk().toArray()));
                ps.setString(4, d.side());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        if (r.error() != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kdms.event_log (job_id, level, stage, message) SELECT job_id, 'ERROR', 'verify', ? FROM kdms.job_table WHERE job_table_id = ?")) {
                ps.setString(1, r.table().srcQualified() + " 검증 실패: " + r.error());
                ps.setLong(2, jobTableId);
                ps.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------------- 원천·대상 동시 실행

    @FunctionalInterface
    interface Query<T> {
        T run() throws SQLException;
    }

    private static <T> List<T> parallel(Query<T> source, Query<T> target) throws SQLException {
        CompletableFuture<T> s = CompletableFuture.supplyAsync(() -> unchecked(source));
        try {
            T g = unchecked(target);
            return List.of(s.join(), g);
        } catch (SqlRuntime e) {
            throw e.cause;
        } catch (CompletionException e) {
            if (e.getCause() instanceof SqlRuntime r) {
                throw r.cause;
            }
            throw e;
        }
    }

    private static <T> T unchecked(Query<T> q) {
        try {
            return q.run();
        } catch (SQLException e) {
            throw new SqlRuntime(e);
        }
    }

    private static final class SqlRuntime extends RuntimeException {
        final SQLException cause;

        SqlRuntime(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
