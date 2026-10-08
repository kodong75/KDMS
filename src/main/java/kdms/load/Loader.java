package kdms.load;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;

import kdms.cdc.CaptureStore;
import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.DdlWriter;
import kdms.ddl.Names;
import kdms.ddl.SchemaApplier;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.Chunks.Chunk;
import kdms.load.LoadState.ChunkRow;
import kdms.load.LoadState.JobTable;
import kdms.rules.Rules;
import kdms.state.SchemaInstaller;

/**
 * 전체 적재(plan.md §4.3). 테이블 병렬 × 테이블 안 구간 병렬. 구간마다 원천을 SNAPSHOT 격리로 읽어 대상에 COPY 하고,
 * 같은 대상 트랜잭션에서 구간 상태를 DONE 으로 바꿔 커밋한다. 그래서 어디서 죽어도 다시 실행하면 끝난 구간은 건너뛰고
 * 커밋되지 않은 구간만 처음부터 다시 넣는다(중복·누락 없음, T-L16).
 * <p>
 * 원천 쓰기가 있는 동안 적재하려면(4단계) 먼저 kdms sync 가 워터마크를 기록해야 한다. 적재는 워터마크보다 뒤 시점을 읽고,
 * 그 사이 변경은 kdms sync 가 다시 적용한다(멱등, docs/cdc.md). 원천 쓰기가 없는 시험은 --no-cdc 로 워터마크 없이 적재한다.
 */
public final class Loader {

    /** 이 상태의 작업에만 적재한다(SYNCING: 동기화 중 일부 테이블을 --reset 으로 다시 적재) */
    static final Set<String> LOADABLE = Set.of("SCHEMA_DONE", "LOADING", "SYNCING", "FAILED");

    /** COPY 로 보내는 묶음 크기(바이트) */
    private static final int COPY_BUFFER = 1 << 20;

    /**
     * @param tables     적재할 원천 테이블("schema.table" 소문자). 비우면 계획의 테이블 전부
     * @param reset      대상 테이블을 비우고(TRUNCATE) 구간 기록을 지운 뒤 처음부터
     * @param postLoad   작업의 테이블이 모두 적재되면 적재 뒤 DDL(UNIQUE·인덱스)을 적용한다
     * @param throttleMs 1,000행마다 쉬는 시간(원천 부하 조절·중단 시험). 0 이면 쉬지 않음
     * @param noCdc      워터마크 없이 적재한다(원천 쓰기가 없을 때만 맞다)
     */
    public record Options(Set<String> tables, boolean reset, boolean postLoad, long throttleMs, boolean noCdc) {
    }

    /** @param status LOADED | FAILED | SKIPPED(이미 적재됨) */
    public record TableResult(String srcTable, String tgtTable, String status, long rows, int chunks, int chunksSkipped,
                              long elapsedMs, String error) {
    }

    /**
     * @param postLoad 적재 뒤 DDL 결과: null(하지 않음), "applied …", 또는 오류
     */
    public record Result(long jobId, List<TableResult> tables, String postLoad, boolean postLoadFailed) {

        public boolean allLoaded() {
            return tables.stream().noneMatch(t -> "FAILED".equals(t.status()));
        }
    }

    /** 사람이 고칠 수 있는 이유로 적재하지 않음(작업 없음, 다른 적재 실행 중 등) */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** 구간 하나가 실패(메시지에 행 값 없음) */
    static final class ChunkFailed extends Exception {
        ChunkFailed(String message) {
            super(message);
        }
    }

    /** 시험용: 구간 커밋 직전에 불린다(예외를 던지면 그 구간은 실패·롤백) */
    interface Hook {
        void beforeCommit(TablePlan t, Chunk chunk) throws SQLException;
    }

    private final KdmsConfig cfg;
    private final Rules rules;
    private final SchemaPlan plan;
    private final Connections db;
    private final PrintWriter out;
    private final String configSha256;
    Hook hook = (t, c) -> {
    };

    public Loader(KdmsConfig cfg, Rules rules, SchemaPlan plan, Connections db, PrintWriter out, String configSha256) {
        this.cfg = cfg;
        this.rules = rules;
        this.plan = plan;
        this.db = db;
        this.out = out;
        this.configSha256 = configSha256;
    }

    public Result run(Options o) throws SQLException, InterruptedException {
        if (plan.blocked()) {
            throw new Refused("계획에 오류가 있어 적재하지 않는다(kdms plan 보고서의 오류 절)");
        }
        try (Connection state = db.target()) {
            SchemaInstaller.install(state);
            try (PreparedStatement ps = state.prepareStatement("SELECT pg_try_advisory_lock(hashtext('kdms.load:' || ?))")) {
                ps.setString(1, cfg.jobName());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        throw new Refused("다른 kdms load 가 작업 " + cfg.jobName() + " 을 적재하고 있다");
                    }
                }
            }
            LoadState.Job job = LoadState.job(state, cfg.jobName());
            if (job == null) {
                throw new Refused("작업 " + cfg.jobName() + " 이 없다. 먼저 kdms schema 로 대상 테이블을 만든다");
            }
            if (!LOADABLE.contains(job.status())) {
                throw new Refused("작업 " + cfg.jobName() + " 은 " + job.status() + " 단계다. 전체 적재는 SCHEMA_DONE·LOADING·SYNCING·FAILED 에서만 한다");
            }
            CaptureStore.Watermark wm = CaptureStore.watermark(state, job.id());
            boolean cdc = wm != null;
            if (!cdc && !o.noCdc()) {
                throw new Refused("워터마크가 없다. 원천 쓰기가 있으면 먼저 다른 터미널에서 kdms sync 를 실행해 \"워터마크 기록\" 이 나온 뒤 적재한다"
                        + "(그래야 적재 중 변경을 놓치지 않는다, docs/cdc.md). 원천 쓰기가 없는 시험이면 --no-cdc");
            }
            if (cdc) {
                out.println("워터마크 " + wm.startLsn() + " 뒤 시점을 적재한다. 적재 중 변경은 kdms sync 가 반영한다");
            }
            if (job.configSha256() != null && !job.configSha256().equals(configSha256)) {
                out.println("주의: kdms schema 때와 설정·규칙 파일 내용이 다르다. 값 규칙이 바뀌었으면 --reset 으로 처음부터 적재한다");
            }

            Map<String, JobTable> registered = LoadState.tables(state, job.id());
            List<TablePlan> selected = select(o.tables());
            List<String[]> pairs = new ArrayList<>();
            for (TablePlan t : selected) {
                JobTable jt = registered.get(Rules.tableKey(t.srcSchema(), t.srcName()));
                if (jt == null || !jt.tgtSchema().equals(t.tgtSchema()) || !jt.tgtTable().equals(t.tgtName())) {
                    throw new Refused(t.srcQualified() + " 이 작업에 등록된 테이블과 다르다(계획이 바뀌었다). kdms schema --replace 로 다시 만든다");
                }
                if (!exists(state, t.tgtQualified())) {
                    throw new Refused("대상 테이블 " + t.tgtQualified() + " 이 없다. kdms schema 로 만든다");
                }
            }
            if (o.reset()) {
                reset(state, selected, registered);
                if (selected.size() == plan.tables().size()) {
                    // 모두 지금 규칙으로 다시 적재하므로 작업의 설정 해시를 바꾼다(다음 실행부터 "설정이 다르다" 주의가 사라진다)
                    LoadState.setConfigSha256(state, job.id(), configSha256);
                }
            }
            LoadState.setJobStatus(state, job.id(), "LOADING", null);
            LoadState.log(state, job.id(), "INFO", "load 시작: 테이블 " + selected.size() + "개, 병렬 " + cfg.load().tableParallelism()
                    + "×" + cfg.load().chunksPerTable() + ", 격리 " + cfg.load().isolation() + (o.reset() ? ", --reset" : ""));

            // 다시 읽는다(reset·이전 실행 상태 반영)
            registered = LoadState.tables(state, job.id());
            List<TableResult> results = loadTables(job.id(), selected, registered, o);

            String postLoad = null;
            boolean postLoadFailed = false;
            boolean failed = results.stream().anyMatch(r -> "FAILED".equals(r.status()));
            Map<String, JobTable> after = LoadState.tables(state, job.id());
            boolean allLoaded = after.values().stream().allMatch(t -> "LOADED".equals(t.status()) || "EXCLUDED".equals(t.status()));
            if (failed) {
                LoadState.setJobStatus(state, job.id(), "LOADING", "적재 실패 테이블 "
                        + results.stream().filter(r -> "FAILED".equals(r.status())).map(TableResult::srcTable).collect(Collectors.joining(", ")));
            } else if (allLoaded && cdc) {
                // 반영 중 일시적 UNIQUE 위반을 피하려고 UNIQUE·인덱스는 전환 때(5단계) 만든다
                postLoad = "적재 뒤 DDL(UNIQUE·인덱스)은 변경분 반영 중이라 지금 적용하지 않는다. 쓰기 중지·반영 완료 뒤 kdms schema --phase post-load";
            } else if (allLoaded && o.postLoad()) {
                try {
                    SchemaApplier.Result r = SchemaApplier.apply(state, plan, DdlWriter.Phase.POST_LOAD, false,
                            new SchemaApplier.Job(cfg.jobName(), cfg.source().host() + ":" + cfg.source().port(), cfg.source().database(),
                                    cfg.target().database(), configSha256));
                    postLoad = "적재 뒤 DDL(UNIQUE·인덱스) 적용: 문장 " + r.statements() + "개"
                            + (r.skipped() > 0 ? ", 이미 있어 건너뜀 " + r.skipped() + "개" : "");
                } catch (SQLException | RuntimeException e) {
                    postLoad = "적재 뒤 DDL(UNIQUE·인덱스) 적용 실패(모두 되돌림): " + SafeMessage.of(e)
                            + "\n  원인을 고친 뒤 kdms schema --phase post-load";
                    postLoadFailed = true;
                }
            } else if (!allLoaded) {
                postLoad = "적재 뒤 DDL 은 작업의 테이블이 모두 적재된 뒤 적용한다(남은 테이블: "
                        + after.values().stream().filter(t -> !"LOADED".equals(t.status()) && !"EXCLUDED".equals(t.status()))
                        .map(t -> t.srcSchema() + "." + t.srcTable()).collect(Collectors.joining(", ")) + ")";
            }
            LoadState.log(state, job.id(), failed ? "ERROR" : "INFO", "load 끝: " + results.stream()
                    .map(r -> r.srcTable() + " " + r.status() + " " + r.rows()).collect(Collectors.joining(", ")));
            return new Result(job.id(), results, postLoad, postLoadFailed);
        }
    }

    private List<TablePlan> select(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return plan.tables();
        }
        List<TablePlan> out = new ArrayList<>();
        for (String n : names) {
            TablePlan t = plan.tables().stream().filter(p -> Rules.tableKey(p.srcSchema(), p.srcName()).equals(n.toLowerCase(Locale.ROOT)))
                    .findFirst().orElseThrow(() -> new Refused("계획에 없는 테이블: " + n + " (원천 이름 schema.table)"));
            out.add(t);
        }
        return out;
    }

    private static boolean exists(Connection c, String qualified) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, qualified);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private void reset(Connection state, List<TablePlan> tables, Map<String, JobTable> registered) throws SQLException {
        boolean auto = state.getAutoCommit();
        state.setAutoCommit(false);
        try (Statement st = state.createStatement()) {
            for (TablePlan t : tables) {
                st.execute("TRUNCATE " + t.tgtQualified());
                LoadState.resetTable(state, registered.get(Rules.tableKey(t.srcSchema(), t.srcName())).id());
            }
            state.commit();
            out.println("--reset: 대상 테이블 " + tables.size() + "개를 비우고 구간 기록을 지웠다");
        } catch (SQLException | RuntimeException e) {
            state.rollback();
            throw e;
        } finally {
            state.setAutoCommit(auto);
        }
    }

    private List<TableResult> loadTables(long jobId, List<TablePlan> tables, Map<String, JobTable> registered, Options o)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(cfg.load().tableParallelism(), Math.max(1, tables.size())),
                named("kdms-table"));
        try {
            List<Future<TableResult>> futures = new ArrayList<>();
            for (TablePlan t : tables) {
                JobTable jt = registered.get(Rules.tableKey(t.srcSchema(), t.srcName()));
                futures.add(pool.submit(() -> loadTable(jobId, t, jt, o)));
            }
            List<TableResult> out = new ArrayList<>();
            for (Future<TableResult> f : futures) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private TableResult loadTable(long jobId, TablePlan t, JobTable jt, Options o) throws InterruptedException {
        long started = System.nanoTime();
        try (Connection state = db.target()) {
            if ("LOADED".equals(jt.status())) {
                List<ChunkRow> rows = LoadState.chunks(state, jt.id());
                long n = rows.stream().mapToLong(r -> r.rowCount() == null ? 0 : r.rowCount()).sum();
                print(t.srcQualified() + ": 이미 적재됨(" + String.format("%,d", n) + "행). 다시 하려면 --reset");
                return new TableResult(t.srcQualified(), t.tgtSchema() + "." + t.tgtName(), "SKIPPED", n, rows.size(), rows.size(), 0, null);
            }
            List<ChunkRow> chunks = LoadState.chunks(state, jt.id());
            if (chunks.isEmpty()) {
                List<Chunk> planned;
                try (Connection src = db.source()) {
                    planned = Chunks.plan(src, t, cfg.load().chunksPerTable());
                }
                LoadState.insertChunks(state, jt.id(), planned);
                chunks = LoadState.chunks(state, jt.id());
            }
            LoadState.tableStarted(state, jt.id());
            List<ChunkRow> todo = chunks.stream().filter(c -> !"DONE".equals(c.status())).toList();
            int skipped = chunks.size() - todo.size();
            if (skipped > 0) {
                print(t.srcQualified() + ": 끝난 구간 " + skipped + "개는 건너뛰고 " + todo.size() + "개를 적재한다");
            }

            List<String> errors = runChunks(t, chunks.size(), todo, o);
            String error = errors.isEmpty() ? null : errors.get(0) + (errors.size() > 1 ? " 외 " + (errors.size() - 1) + "건" : "");
            long rows = LoadState.tableFinished(state, jt.id(), error);
            long ms = (System.nanoTime() - started) / 1_000_000;
            LoadState.log(state, jobId, error == null ? "INFO" : "ERROR", t.srcQualified() + " → " + t.tgtSchema() + "." + t.tgtName()
                    + (error == null ? " 적재 " + rows + "행" : " 실패: " + error));
            print(t.srcQualified() + " → " + t.tgtSchema() + "." + t.tgtName() + ": " + (error == null ? "완료" : "실패") + " "
                    + String.format("%,d", rows) + "행, 구간 " + chunks.size() + "개" + (skipped > 0 ? "(건너뜀 " + skipped + ")" : "")
                    + ", " + seconds(ms) + (error == null ? "" : "\n  오류: " + error));
            return new TableResult(t.srcQualified(), t.tgtSchema() + "." + t.tgtName(), error == null ? "LOADED" : "FAILED", rows,
                    chunks.size(), skipped, ms, error);
        } catch (SQLException e) {
            String error = SafeMessage.of(e);
            print(t.srcQualified() + ": 실패 " + error);
            return new TableResult(t.srcQualified(), t.tgtSchema() + "." + t.tgtName(), "FAILED", 0, 0, 0,
                    (System.nanoTime() - started) / 1_000_000, error);
        }
    }

    private List<String> runChunks(TablePlan t, int total, List<ChunkRow> todo, Options o) throws InterruptedException {
        List<String> errors = new ArrayList<>();
        if (todo.isEmpty()) {
            return errors;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(cfg.load().chunksPerTable(), todo.size()), named("kdms-chunk"));
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (ChunkRow c : todo) {
                futures.add(pool.submit(() -> loadChunk(t, total, c, o)));
            }
            for (Future<String> f : futures) {
                try {
                    String e = f.get();
                    if (e != null) {
                        errors.add(e);
                    }
                } catch (ExecutionException e) {
                    errors.add(SafeMessage.of(e.getCause()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return errors;
    }

    /** @return 실패하면 오류 메시지(행 값 없음), 성공하면 null */
    private String loadChunk(TablePlan t, int total, ChunkRow row, Options o) throws SQLException, InterruptedException {
        String label = t.srcQualified() + " 구간 " + row.chunk().no() + "/" + total;
        long started = System.nanoTime();
        try (Connection tgt = db.target()) {
            LoadState.chunkRunning(tgt, row.id());
            try {
                long rows = copy(t, row, tgt, o);
                long ms = (System.nanoTime() - started) / 1_000_000;
                print(label + ": " + String.format("%,d", rows) + "행, " + seconds(ms));
                return null;
            } catch (ChunkFailed | SQLException | RuntimeException e) {
                String msg = e instanceof ChunkFailed ? e.getMessage() : SafeMessage.of(e);
                tgt.setAutoCommit(true);
                LoadState.chunkFailed(tgt, row.id(), msg);
                print(label + ": 실패 " + msg);
                return "구간 " + row.chunk().no() + ": " + msg;
            }
        }
    }

    /** 구간 하나: 원천 읽기(SNAPSHOT) → COPY → 구간 DONE → 커밋. 실패하면 대상은 롤백되어 아무것도 남지 않는다 */
    private long copy(TablePlan t, ChunkRow row, Connection tgt, Options o) throws SQLException, ChunkFailed, InterruptedException {
        List<ColumnPlan> cols = t.loadColumns();
        List<CopyValues.Column> conv = CopyValues.columns(cols, rules.text().nulReplacement(), rules.sentinelValues());
        List<String> params = new ArrayList<>();
        String select = Chunks.selectSql(t, cols, row.chunk(), params);
        String copySql = "COPY " + t.tgtQualified() + " (" + cols.stream().map(c -> Names.quote(c.tgtName())).collect(Collectors.joining(", "))
                + ") FROM STDIN (FORMAT text)";
        long started = System.nanoTime();
        try (Connection src = db.source()) {
            src.setAutoCommit(false);
            src.setReadOnly(true);
            if ("snapshot".equals(cfg.load().isolation())) {
                src.setTransactionIsolation(com.microsoft.sqlserver.jdbc.ISQLServerConnection.TRANSACTION_SNAPSHOT);
            } else {
                src.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            tgt.setAutoCommit(false);
            try (Statement st = tgt.createStatement()) {
                st.execute("SET LOCAL synchronous_commit TO off");
            }
            long rows = 0;
            CopyIn copy = tgt.unwrap(PGConnection.class).getCopyAPI().copyIn(copySql);
            boolean copying = true;
            try (PreparedStatement ps = src.prepareStatement(select)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                ps.setFetchSize(1000);
                try (ResultSet rs = executeSource(ps)) {
                    StringBuilder line = new StringBuilder(256);
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(COPY_BUFFER + 65536);
                    while (rs.next()) {
                        line.setLength(0);
                        boolean ok = true;
                        for (int i = 0; i < conv.size(); i++) {
                            if (i > 0) {
                                line.append('\t');
                            }
                            ok &= CopyValues.append(line, rs, i + 1, conv.get(i));
                        }
                        rows++;
                        if (!ok && copying) {
                            copy.cancelCopy(); // nul_char: fail → 이 구간은 넣지 않고 나머지 행의 NUL 만 센다
                            copying = false;
                        }
                        if (copying) {
                            line.append('\n');
                            byte[] b = line.toString().getBytes(StandardCharsets.UTF_8);
                            buf.write(b, 0, b.length);
                            if (buf.size() >= COPY_BUFFER) {
                                copy.writeToCopy(buf.toByteArray(), 0, buf.size());
                                buf.reset();
                            }
                        }
                        if (o.throttleMs() > 0 && rows % 1000 == 0) {
                            Thread.sleep(o.throttleMs());
                        }
                    }
                    if (copying && buf.size() > 0) {
                        copy.writeToCopy(buf.toByteArray(), 0, buf.size());
                    }
                }
                if (!copying) {
                    tgt.rollback();
                    src.commit();
                    String nul = conv.stream().filter(c -> c.nulRows() > 0)
                            .map(c -> t.srcQualified() + "." + c.plan().srcName() + " " + c.nulRows() + "행")
                            .collect(Collectors.joining(", "));
                    throw new ChunkFailed("NUL 문자(A02)가 든 값: " + nul + ". 규칙 text.nul_char: fail 이라 적재하지 않았다. "
                            + "컬럼별로 tables.<테이블>.columns.<컬럼>.nul_char: strip | replace 로 정한다(docs/load-verify.md §4)");
                }
                long copied = copy.endCopy();
                copying = false;
                hook.beforeCommit(t, row.chunk());
                LoadState.chunkDone(tgt, row.id(), copied, (System.nanoTime() - started) / 1_000_000);
                tgt.commit();
                src.commit();
                return copied;
            } finally {
                if (copying && copy.isActive()) {
                    copy.cancelCopy();
                }
                if (!tgt.getAutoCommit()) {
                    tgt.rollback();
                }
                src.rollback();
            }
        }
    }

    private static ResultSet executeSource(PreparedStatement ps) throws SQLException {
        try {
            return ps.executeQuery();
        } catch (SQLException e) {
            if (e.getErrorCode() == 3952) {
                throw new SQLException("원천 DB 에 스냅숏 격리가 꺼져 있다. DBA 가 ALTER DATABASE … SET ALLOW_SNAPSHOT_ISOLATION ON "
                        + "(docs/test-env.md §2) 또는 설정 load.isolation: read_committed(원천 쓰기가 없을 때만)", e.getSQLState(), e);
            }
            throw e;
        }
    }

    private synchronized void print(String line) {
        out.println(line);
        out.flush();
    }

    static String seconds(long ms) {
        return String.format("%.1f초", ms / 1000.0);
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread th = new Thread(r, prefix + "-" + n.incrementAndGet());
            th.setDaemon(true);
            return th;
        };
    }
}
