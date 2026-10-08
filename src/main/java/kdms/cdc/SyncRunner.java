package kdms.cdc;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.kafka.connect.source.SourceRecord;

import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import kdms.apply.Applier;
import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.SafeMessage;
import kdms.rules.Rules;
import kdms.state.SchemaInstaller;

/**
 * kdms sync: 변경분 수집(Debezium Embedded → kdms.change_log)과 반영(change_log → 대상 테이블)을 한 프로세스에서 돌린다.
 * plan.md §4.3 ①·§4.4, docs/cdc.md.
 * <ol>
 * <li>엔진이 스트리밍을 시작하면(첫 하트비트) 워터마크를 기록한다. 그 뒤에 시작한 kdms load 의 구간은 모두 워터마크보다 뒤 시점이다</li>
 * <li>수집: 배치마다 change_log 에 한 트랜잭션으로 저장한 뒤 Debezium 오프셋을 커밋한다</li>
 * <li>반영: 적재가 끝난 테이블의 변경을 LSN 순서로 적용({@link Applier})</li>
 * <li>status_seconds 마다 진행·지연을 찍고 kdms.watermark 에 남긴다</li>
 * <li>--drain: 원천 쓰기가 멈춘 뒤 마지막 변경까지 반영했으면 끝낸다</li>
 * </ol>
 * 중지: Ctrl+C(엔진을 닫고 진행 중 배치를 마친다). 강제 종료돼도 다시 실행하면 커밋된 오프셋 다음부터 이어 받는다(T-C08).
 */
public final class SyncRunner {

    /** 반영·수집 실패, 원천 DDL 변경, CDC 보존 기간 초과 */
    public static final int FAILED = 5;

    /** 동기화할 수 있는 작업 상태 */
    static final Set<String> SYNCABLE = Set.of("SCHEMA_DONE", "LOADING", "SYNCING", "FAILED");

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** @param drain 원천 쓰기가 멈췄다고 보고, 마지막 변경까지 반영하면 끝낸다 */
    public record Options(boolean drain) {
    }

    /** 사람이 고칠 수 있는 이유로 시작하지 않음 */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private final KdmsConfig cfg;
    private final Rules rules;
    private final SchemaPlan plan;
    private final Connections db;
    private final PrintWriter out;

    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<String> streamLsn = new AtomicReference<>();
    private final AtomicReference<String> engineError = new AtomicReference<>();
    private final AtomicBoolean engineDone = new AtomicBoolean();
    private final AtomicBoolean streaming = new AtomicBoolean();
    private final AtomicLong captured = new AtomicLong();

    public SyncRunner(KdmsConfig cfg, Rules rules, SchemaPlan plan, Connections db, PrintWriter out) {
        this.cfg = cfg;
        this.rules = rules;
        this.plan = plan;
        this.db = db;
        this.out = out;
    }

    /** Ctrl+C 등에서 부른다. 진행 중 배치를 마치고 멈춘다 */
    public void requestStop() {
        stop.set(true);
    }

    /** @return 종료 코드: 0 정상 중지·drain 완료, {@link #FAILED} 실패 */
    public int run(Options o) throws SQLException, InterruptedException {
        if (plan.blocked()) {
            throw new Refused("계획에 오류가 있어 동기화하지 않는다(kdms plan 보고서의 오류 절)");
        }
        List<TablePlan> cdcTables = plan.tables().stream().filter(t -> t.primaryKey() != null).toList();
        List<TablePlan> noPk = plan.tables().stream().filter(t -> t.primaryKey() == null).toList();
        try (Connection state = db.target()) {
            SchemaInstaller.install(state);
            lock(state);
            long jobId = jobId(state);
            checkTables(state, jobId);
            checkCaptured(cdcTables);
            checkTargetTriggersAndForeignKeys(state, cdcTables);

            CaptureStore.createDebeziumTables(state, jobId);
            CaptureStore.Watermark wm = CaptureStore.watermark(state, jobId);
            if (wm != null && CaptureStore.offsetRows(state, jobId) == 0) {
                throw new Refused("워터마크(" + wm.startLsn() + ")는 있는데 Debezium 오프셋(kdms." + DebeziumProps.offsetTable(jobId)
                        + ")이 비어 있다. 이어 받을 위치를 몰라 변경이 빠질 수 있으므로 시작하지 않는다. kdms reset --yes 뒤 전체 적재부터 다시 한다");
            }
            out.println("KDMS 변경분 동기화 · 작업 " + cfg.jobName() + " (job_id " + jobId + ") · " + cfg.source() + " → " + cfg.target()
                    + " · 캡처 테이블 " + cdcTables.size() + "개" + (o.drain() ? " · --drain" : ""));
            if (!noPk.isEmpty()) {
                out.println("주의: PK 가 없는 테이블은 변경분을 반영하지 않는다(전환 때 전체 재적재, plan.md §4.6): "
                        + noPk.stream().map(TablePlan::srcQualified).collect(Collectors.joining(", ")));
            }
            out.println(wm == null ? "워터마크 없음: 스트리밍이 시작되면 기록한다. 그 뒤에 kdms load 를 시작한다"
                    : "워터마크 " + wm.startLsn() + " (" + wm.recordedAt().toLocalDateTime().withNano(0) + " 기록) · 저장된 오프셋 다음부터 이어 받는다");
            out.flush();
            log(state, jobId, "INFO", "sync 시작" + (o.drain() ? " (--drain)" : "") + ", 캡처 테이블 " + cdcTables.size());

            Properties props = DebeziumProps.build(cfg, jobId, cdcTables.stream().map(TablePlan::srcQualified).toList());
            Captured parser = new Captured(cdcTables);
            DebeziumEngine<RecordChangeEvent<SourceRecord>> engine = DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
                    .using(props)
                    .notifying(new Consumer(jobId, parser))
                    .using((success, message, error) -> {
                        if (!success || error != null) {
                            engineError.compareAndSet(null, explain(message, error));
                        }
                        engineDone.set(true);
                    })
                    .build();
            ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "kdms-cdc");
                t.setDaemon(true);
                return t;
            });
            ex.execute(engine);
            int code;
            try (Connection applyConn = db.target()) {
                Applier applier = new Applier(jobId, cdcTables, rules, applyConn, cfg.sync().batchSize());
                try {
                    code = loop(o, state, jobId, applier);
                } finally {
                    applier.close();
                }
            } finally {
                try {
                    engine.close();
                } catch (Exception e) {
                    // 이미 멈춘 엔진
                }
                ex.shutdown();
                if (!ex.awaitTermination(60, TimeUnit.SECONDS)) {
                    out.println("주의: Debezium 엔진이 60초 안에 멈추지 않았다");
                }
            }
            if (engineError.get() != null && code == 0) {
                out.println("수집 오류: " + engineError.get());
                code = FAILED;
            }
            log(state, jobId, code == 0 ? "INFO" : "ERROR", "sync 끝: 종료 코드 " + code
                    + (engineError.get() != null ? ", 수집 오류: " + engineError.get() : ""));
            out.flush();
            return code;
        }
    }

    /** 반영·상태 표시·drain 확인 */
    private int loop(Options o, Connection state, long jobId, Applier applier) throws SQLException, InterruptedException {
        long statusEvery = cfg.sync().statusSeconds() * 1000L;
        long nextStatus = System.currentTimeMillis() + 2000;
        long applied = 0;
        Drain drain = o.drain() ? new Drain() : null;
        Connection src = null;
        try {
            while (true) {
                if (stop.get()) {
                    out.println("중지 요청: 진행 중 배치를 마치고 멈춘다");
                    return 0;
                }
                if (engineDone.get()) {
                    String err = engineError.get();
                    out.println(err == null ? "Debezium 엔진이 멈췄다" : "수집 오류: " + err);
                    engineError.set(null); // 아래에서 다시 찍지 않게
                    return FAILED;
                }
                Applier.Batch b;
                try {
                    b = applier.applyOnce();
                } catch (Applier.Failed e) {
                    out.println("반영 실패(이 배치는 되돌렸다. change_log 에 남아 있다): " + e.getMessage());
                    log(state, jobId, "ERROR", "반영 실패: " + e.getMessage());
                    return FAILED;
                }
                applied += b.applied();
                long now = System.currentTimeMillis();
                boolean due = now >= nextStatus;
                if (due || (drain != null && drain.dueAt <= now)) {
                    if (src == null || src.isClosed()) {
                        src = openSource();
                    }
                    Status s = status(state, src, jobId);
                    if (due) {
                        print(s, applied);
                        nextStatus = now + statusEvery;
                    }
                    if (drain != null && drain.check(s, src, now)) {
                        print(s, applied);
                        out.println("따라잡음(--drain): 원천 마지막 변경 " + (s.src == null ? "-" : s.src.maxTxLsn())
                                + " 까지 반영, 반영 대기 0건 · " + Duration.ofMillis(now - drain.started).toSeconds() + "초");
                        out.println("다음: kdms verify");
                        log(state, jobId, "INFO", "sync --drain 따라잡음: " + (s.src == null ? "-" : s.src.maxTxLsn()));
                        return 0;
                    }
                }
                if (b.applied() == 0) {
                    Thread.sleep(cfg.sync().pollMs());
                }
            }
        } finally {
            if (src != null) {
                src.close();
            }
        }
    }

    private Connection openSource() {
        try {
            return db.source();
        } catch (SQLException e) {
            out.println("원천 상태 조회 실패(계속한다): " + SafeMessage.of(e));
            return null;
        }
    }

    /** 상태 한 번 */
    record Status(SourceCdc.Snapshot src, String stream, long pending, long pendingWaitingLoad, int notLoaded, String applied,
                  BigDecimal lagSeconds, long capturedTotal, long appliedTotal) {

        /** 원천의 마지막 변경까지 Debezium 이 처리했고 반영 대기가 없다 */
        boolean caughtUp() {
            return pending == 0 && (src == null || src.maxTxLsn() == null || (stream != null && stream.compareTo(src.maxTxLsn()) >= 0));
        }
    }

    private Status status(Connection state, Connection src, long jobId) throws SQLException {
        long pending;
        long waiting;
        int notLoaded;
        String applied;
        long capturedTotal;
        long appliedTotal;
        try (PreparedStatement ps = state.prepareStatement("""
                SELECT (SELECT count(*) FROM kdms.change_log WHERE job_id = ?),
                       (SELECT count(*) FROM kdms.change_log c WHERE c.job_id = ? AND NOT EXISTS (
                           SELECT 1 FROM kdms.job_table t WHERE t.job_id = c.job_id AND t.status = 'LOADED'
                             AND lower(t.src_schema) = lower(c.src_schema) AND lower(t.src_table) = lower(c.src_table))),
                       (SELECT count(*) FROM kdms.job_table WHERE job_id = ? AND has_pk AND status NOT IN ('LOADED', 'EXCLUDED')),
                       w.applied_commit_lsn, coalesce(w.changes_captured, 0), coalesce(w.changes_applied, 0)
                FROM (SELECT 1) d LEFT JOIN kdms.watermark w ON w.job_id = ?""")) {
            ps.setLong(1, jobId);
            ps.setLong(2, jobId);
            ps.setLong(3, jobId);
            ps.setLong(4, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                pending = rs.getLong(1);
                waiting = rs.getLong(2);
                notLoaded = rs.getInt(3);
                applied = rs.getString(4);
                capturedTotal = rs.getLong(5);
                appliedTotal = rs.getLong(6);
            }
        }
        SourceCdc.Snapshot snap = null;
        BigDecimal lag = null;
        String stream = streamLsn.get();
        if (src != null) {
            try {
                snap = SourceCdc.snapshot(src);
                Status probe = new Status(snap, stream, pending, waiting, notLoaded, applied, null, capturedTotal, appliedTotal);
                if (probe.caughtUp()) {
                    lag = BigDecimal.ZERO;
                } else if (snap.maxTxAt() != null) {
                    // 지연 = 원천 마지막 변경 커밋 시각 − 대상에 반영한 마지막 변경의 커밋 시각(둘 다 원천 시계, plan.md §4.4)
                    LocalDateTime appliedAt = SourceCdc.timeOf(src, applied);
                    if (appliedAt == null) {
                        CaptureStore.Watermark wm = CaptureStore.watermark(state, jobId);
                        appliedAt = wm == null ? null : SourceCdc.timeOf(src, wm.startLsn());
                    }
                    if (appliedAt != null) {
                        lag = BigDecimal.valueOf(Math.max(0, Duration.between(appliedAt, snap.maxTxAt()).toMillis()))
                                .movePointLeft(3).setScale(3, RoundingMode.UNNECESSARY);
                    }
                }
                if (snap.captureStale()) {
                    // 캡처 Job 이 멈추면 lsn_time_mapping 도 멈춰 위 지연이 0 으로 보인다. 마지막 훑기 뒤 지난 시간을 지연으로 본다(T-C09)
                    BigDecimal stale = BigDecimal.valueOf(snap.sinceLastScanSeconds()).setScale(3);
                    lag = lag == null || stale.compareTo(lag) > 0 ? stale : lag;
                }
            } catch (SQLException e) {
                out.println("원천 상태 조회 실패(계속한다): " + SafeMessage.of(e));
                src.close();
                snap = null;
            }
        }
        try (PreparedStatement ps = state.prepareStatement("""
                UPDATE kdms.watermark SET stream_lsn = ?, src_max_lsn = ?, src_max_lsn_at = ?, pending_changes = ?, lag_seconds = ?,
                       sync_status_at = now()
                WHERE job_id = ?""")) {
            ps.setString(1, stream);
            ps.setString(2, snap == null ? null : snap.maxTxLsn());
            ps.setObject(3, snap == null ? null : snap.maxTxAt());
            ps.setLong(4, pending);
            ps.setBigDecimal(5, lag);
            ps.setLong(6, jobId);
            ps.executeUpdate();
        }
        if (notLoaded == 0) {
            // 모든 테이블이 적재돼 반영 중이면 작업 상태를 SYNCING 으로(적재 중에는 kdms load 가 LOADING 으로 둔다)
            try (PreparedStatement ps = state.prepareStatement("""
                    UPDATE kdms.job SET status = 'SYNCING', updated_at = now()
                    WHERE job_id = ? AND status IN ('SCHEMA_DONE', 'LOADING')
                      AND EXISTS (SELECT 1 FROM kdms.watermark WHERE job_id = ?)""")) {
                ps.setLong(1, jobId);
                ps.setLong(2, jobId);
                ps.executeUpdate();
            }
        }
        return new Status(snap, stream, pending, waiting, notLoaded, applied, lag, capturedTotal, appliedTotal);
    }

    private void print(Status s, long appliedThisRun) {
        StringBuilder b = new StringBuilder();
        b.append('[').append(LocalTime.now().format(HMS)).append("] ");
        b.append("수집 ").append(String.format("%,d", s.capturedTotal)).append(" · 반영 ").append(String.format("%,d", s.appliedTotal));
        b.append(" · 대기 ").append(String.format("%,d", s.pending));
        if (s.pendingWaitingLoad > 0 || s.notLoaded > 0) {
            b.append("(적재 전 테이블 ").append(s.notLoaded).append("개, 그 변경 ").append(String.format("%,d", s.pendingWaitingLoad)).append(")");
        }
        b.append(" · 지연 ").append(s.lagSeconds == null ? "-" : s.lagSeconds.setScale(1, RoundingMode.HALF_UP) + "초");
        if (s.src != null && s.src.maxTxAt() != null) {
            b.append(" · 원천 마지막 변경 ").append(s.src.maxTxAt().toLocalTime().withNano(0));
        }
        if (s.src != null && s.src.captureStale()) {
            b.append(" · 원천 캡처 Job 이 ").append(s.src.sinceLastScanSeconds()).append("초째 로그를 읽지 않음(SQL Agent 확인)");
            if ("REPLICATION".equalsIgnoreCase(s.src.logReuseWait())) {
                b.append(" · 원천 로그 REPLICATION 대기(로그가 쌓이는 중)");
            }
        }
        if (!streaming.get()) {
            b.append(" · 스트리밍 시작 전");
        }
        out.println(b);
        out.flush();
    }

    /**
     * --drain 판정. 원천 쓰기를 멈춘 뒤에 쓴다.
     * <ol>
     * <li>지금까지 캡처된 마지막 변경까지 반영했으면(반영 대기 0, 스트림 위치 ≥ 원천 마지막 커밋 LSN) 그 순간의 원천 시각을 표시(mark)로 잡는다</li>
     * <li>캡처 Job 이 mark 뒤에 시작한 로그 훑기를 끝냈는데 mark 뒤 커밋이 하나도 없으면 끝낸다.
     *     mark 전에 커밋됐지만 아직 캡처 안 된 변경은 그 훑기가 잡으므로 위치가 앞서 1 로 돌아간다</li>
     * <li>mark 뒤 커밋이 보이면 원천 쓰기가 아직 있다는 뜻이다. 알리고 mark 를 다시 잡는다</li>
     * </ol>
     * 캡처 Job 은 기본 5초마다 로그를 훑는다. 그래서 끝나기까지 보통 5~10초 걸린다.
     */
    private final class Drain {
        final long started = System.currentTimeMillis();
        long dueAt = started;
        LocalDateTime since;
        LocalDateTime mark;
        boolean announcedLoad;
        boolean announcedWrites;

        boolean check(Status s, Connection src, long now) throws SQLException {
            dueAt = now + 2000;
            if (src == null || s.src == null) {
                mark = null;
                return false;
            }
            if (since == null) {
                since = s.src.at();
                out.println("drain 기준 시각(원천): " + since.withNano(0) + " · 이 시각까지 커밋된 변경을 모두 반영하고 원천 쓰기가 없으면 끝낸다");
            }
            if (s.notLoaded > 0) {
                if (!announcedLoad) {
                    out.println("적재가 끝나지 않은 테이블이 " + s.notLoaded + "개 있다. kdms load 가 끝날 때까지 기다린다");
                    announcedLoad = true;
                }
                mark = null;
                return false;
            }
            if (!announcedWrites && s.src.maxTxAt() != null && s.src.maxTxAt().isAfter(since)) {
                out.println("원천 쓰기가 아직 있다(drain 시작 뒤 커밋 " + s.src.maxTxAt().withNano(0) + "). 쓰기를 멈출 때까지 반영을 계속한다");
                announcedWrites = true;
            }
            if (!streaming.get() || !s.caughtUp()) {
                mark = null;
                return false;
            }
            if (mark == null || (s.src.maxTxAt() != null && s.src.maxTxAt().isAfter(mark))) {
                mark = s.src.at();
                return false;
            }
            if (!SourceCdc.scannedSince(src, mark)) {
                return false;
            }
            // 훑기 확인 뒤에 다시 본다(확인과 상태 조회 사이에 끝난 훑기가 새 커밋을 잡았을 수 있다)
            SourceCdc.Snapshot again = SourceCdc.snapshot(src);
            String stream = streamLsn.get();
            return (again.maxTxAt() == null || !again.maxTxAt().isAfter(mark))
                    && (again.maxTxLsn() == null || (stream != null && stream.compareTo(again.maxTxLsn()) >= 0));
        }
    }

    /** Debezium 배치 → change_log. 이 메서드가 예외를 던지면 엔진이 멈추고 오프셋은 커밋되지 않는다 */
    private final class Consumer implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {
        private final long jobId;
        private final Captured parser;
        private Connection conn;

        Consumer(long jobId, Captured parser) {
            this.jobId = jobId;
            this.parser = parser;
        }

        @Override
        public void handleBatch(List<RecordChangeEvent<SourceRecord>> records,
                                DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer) throws InterruptedException {
            List<Captured.Record> changes = new ArrayList<>();
            String start = null;
            String last = null;
            for (RecordChangeEvent<SourceRecord> r : records) {
                Captured.Record c = parser.parse(r.record());
                switch (c.kind()) {
                    case CHANGE -> changes.add(c);
                    case HEARTBEAT -> {
                    }
                    case SCHEMA_SNAPSHOT -> {
                        continue;
                    }
                    case SCHEMA_CHANGE -> {
                        if (streaming.get()) {
                            throw new IllegalStateException("원천 스키마 변경(DDL)을 감지했다. 적재·동기화 기간에는 원천 스키마를 바꾸지 않는다(plan.md R6). "
                                    + "kdms plan 으로 확인하고 kdms reset 뒤 처음부터 다시 한다");
                        }
                        continue;
                    }
                    default -> throw new IllegalStateException(c.kind().name());
                }
                if (c.streamLsn() != null) {
                    start = start == null || c.streamLsn().compareTo(start) < 0 ? c.streamLsn() : start;
                    last = Lsn.max(last, c.streamLsn());
                }
            }
            CaptureStore.BatchResult br = write(changes, start);
            if (br.watermark() != null) {
                synchronized (out) {
                    out.println("워터마크 기록: " + br.watermark() + " · 스트리밍 시작. 이제 다른 터미널에서 kdms load 를 실행할 수 있다");
                    out.flush();
                }
            }
            for (RecordChangeEvent<SourceRecord> r : records) {
                committer.markProcessed(r);
            }
            committer.markBatchFinished();
            if (start != null) {
                streaming.set(true);
            }
            if (last != null) {
                streamLsn.accumulateAndGet(last, Lsn::max);
            }
            captured.addAndGet(br.inserted());
        }

        private CaptureStore.BatchResult write(List<Captured.Record> changes, String start) {
            for (int attempt = 1; ; attempt++) {
                try {
                    if (conn == null || conn.isClosed()) {
                        conn = db.target();
                    }
                    return CaptureStore.write(conn, jobId, changes, start);
                } catch (SQLException e) {
                    try {
                        if (conn != null) {
                            conn.close();
                        }
                    } catch (SQLException ignored) {
                        // 끊긴 연결
                    }
                    conn = null;
                    if (attempt >= 2) {
                        throw new IllegalStateException("change_log 저장 실패: " + SafeMessage.of(e), e);
                    }
                }
            }
        }

        @Override
        public boolean supportsTombstoneEvents() {
            return false;
        }
    }

    /** Debezium 오류를 사람이 할 일로 바꾼다(T-C10 보존 기간 초과 등). 행 값은 넣지 않는다 */
    static String explain(String message, Throwable error) {
        StringBuilder all = new StringBuilder(message == null ? "" : message);
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(" / ").append(t.getMessage());
        }
        String s = all.toString();
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("no longer available")) {
            return "원천 CDC 보존 기간이 지나 마지막 위치 뒤의 변경이 지워졌다(T-C10). 이어 받을 수 없으므로 kdms reset --yes 뒤 "
                    + "kdms sync → kdms load 로 전체 적재부터 다시 한다";
        }
        if (low.contains("permission") || low.contains("denied")) {
            return "원천 권한 부족: " + SafeMessage.of(s) + " (test/sql/mssql/20_grant_kdms_login.sql)";
        }
        return SafeMessage.of(s);
    }

    private void lock(Connection state) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement("SELECT pg_try_advisory_lock(hashtext('kdms.sync:' || ?))")) {
            ps.setString(1, cfg.jobName());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new Refused("다른 kdms sync 가 작업 " + cfg.jobName() + " 을 동기화하고 있다(pgrep -fl kdms.jar)");
                }
            }
        }
    }

    private long jobId(Connection state) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement("SELECT job_id, status FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, cfg.jobName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Refused("작업 " + cfg.jobName() + " 이 없다. 먼저 kdms schema 로 대상 테이블을 만든다");
                }
                if (!SYNCABLE.contains(rs.getString(2))) {
                    throw new Refused("작업 " + cfg.jobName() + " 은 " + rs.getString(2) + " 단계다. 동기화는 SCHEMA_DONE·LOADING·SYNCING·FAILED 에서만 한다");
                }
                return rs.getLong(1);
            }
        }
    }

    /** 계획 테이블이 작업에 등록된 것과 같은가(kdms load 와 같은 검사) */
    private void checkTables(Connection state, long jobId) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement(
                "SELECT lower(src_schema || '.' || src_table), tgt_schema, tgt_table FROM kdms.job_table WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            java.util.Map<String, String> reg = new java.util.HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reg.put(rs.getString(1), rs.getString(2) + "." + rs.getString(3));
                }
            }
            for (TablePlan t : plan.tables()) {
                String r = reg.get(t.srcQualified().toLowerCase(Locale.ROOT));
                if (r == null || !r.equals(t.tgtSchema() + "." + t.tgtName())) {
                    throw new Refused(t.srcQualified() + " 이 작업에 등록된 테이블과 다르다(계획이 바뀌었다). kdms schema --replace 로 다시 만든다");
                }
            }
        }
    }

    /** PK 있는 계획 테이블이 모두 원천 CDC 로 캡처되는가(plan.md §2.1, test/sql/mssql/10_enable_cdc.sql) */
    private void checkCaptured(List<TablePlan> tables) throws SQLException {
        Set<String> captured;
        try (Connection src = db.source()) {
            captured = SourceCdc.capturedTables(src);
        }
        List<String> missing = tables.stream().map(TablePlan::srcQualified)
                .filter(n -> !captured.contains(n.toLowerCase(Locale.ROOT))).toList();
        if (!missing.isEmpty()) {
            throw new Refused("원천 CDC 캡처 인스턴스가 없는 테이블: " + String.join(", ", missing)
                    + ". DBA 가 sys.sp_cdc_enable_table 로 켠다(test/sql/mssql/10_enable_cdc.sql). 이 로그인에 cdc 스키마 읽기 권한이 없어도 이렇게 보인다");
        }
    }

    /** 반영 중에는 대상에 트리거·FK 가 없어야 한다(G13 이력 중복, 테이블끼리 순서를 맞추지 않는다). 둘 다 전환 때 켠다 */
    private void checkTargetTriggersAndForeignKeys(Connection state, List<TablePlan> tables) throws SQLException {
        List<String> names = tables.stream().map(TablePlan::tgtQualified).toList();
        try (PreparedStatement ps = state.prepareStatement("""
                SELECT 'trigger ' || tgname || ' ON ' || tgrelid::regclass FROM pg_trigger
                WHERE NOT tgisinternal AND tgenabled <> 'D' AND tgrelid = ANY (SELECT to_regclass(x) FROM unnest(?::text[]) x)
                UNION ALL
                SELECT 'FK ' || conname || ' ON ' || conrelid::regclass FROM pg_constraint
                WHERE contype = 'f' AND conrelid = ANY (SELECT to_regclass(x) FROM unnest(?::text[]) x)""")) {
            java.sql.Array a = state.createArrayOf("text", names.toArray());
            ps.setArray(1, a);
            ps.setArray(2, a);
            List<String> found = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
            }
            if (!found.isEmpty()) {
                throw new Refused("대상 테이블에 트리거·FK 가 있어 반영하지 않는다(반영 중 이력 중복 G13, 테이블 간 순서): "
                        + String.join(", ", found) + ". 트리거는 끄고(ALTER TABLE … DISABLE TRIGGER) FK 는 전환 때 만든다");
            }
        }
    }

    private static void log(Connection c, long jobId, String level, String message) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kdms.event_log (job_id, level, stage, message) VALUES (?, ?, 'sync', ?)")) {
            ps.setLong(1, jobId);
            ps.setString(2, level);
            ps.setString(3, message.length() > 2000 ? message.substring(0, 2000) : message);
            ps.executeUpdate();
        }
    }

    /** 시험용: 엔진이 스트리밍을 시작할 때까지 기다린다 */
    boolean awaitStreaming(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!streaming.get() && System.currentTimeMillis() < end && !engineDone.get()) {
            Thread.sleep(200);
        }
        return streaming.get();
    }
}
