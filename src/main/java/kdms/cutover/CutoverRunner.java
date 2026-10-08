package kdms.cutover;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import kdms.cdc.CaptureStore;
import kdms.cdc.SourceCdc;
import kdms.cdc.SyncRunner;
import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.DdlWriter.Phase;
import kdms.ddl.SchemaApplier;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.Loader;
import kdms.load.SafeMessage;
import kdms.rules.Rules;
import kdms.state.SchemaInstaller;
import kdms.verify.Verifier;
import kdms.verify.VerifyReport;

/**
 * kdms cutover: 전환(plan.md §4.5, KIS:docs/zero-downtime-rehearsal.md §4). 사람이 원천 쓰기를 멈춘 뒤 실행한다.
 * <pre>
 * SYNCING ─(시작)─> CUTOVER ─①마지막 반영 ②PK 없는 테이블 재적재 ③UNIQUE·인덱스 ④검증─> VERIFIED ─⑤setval ⑥FK─> DONE
 *                      └──────────── 어느 단계든 실패·불일치 ────────────> FAILED (다시 kdms cutover 또는 kdms sync)
 * </pre>
 * 모든 단계는 다시 실행해도 결과가 같다. 그래서 중간에 죽으면(CUTOVER·VERIFIED 에 남음) kdms cutover 를 다시 실행하면 처음 단계부터
 * 다시 돌아 같은 곳에 닿는다. 시작~끝 시간이 예상 다운타임이다(kdms.cutover_run.elapsed_ms).
 */
public final class CutoverRunner {

    /** 검증 불일치·단계 실패 */
    public static final int FAILED = 5;

    /** 전환을 시작할 수 있는 작업 상태(LOADING 은 --no-cdc 로 적재만 한 작업) */
    static final Set<String> STARTABLE = Set.of("SYNCING", "LOADING", "CUTOVER", "VERIFIED", "FAILED");

    /**
     * @param noCdc          워터마크 없이 적재한 작업(원천 쓰기 없음): 마지막 반영을 건너뛴다
     * @param maxWaitSeconds 마지막 반영이 이 시간 안에 끝나지 않으면 멈춘다(원천 쓰기가 아직 있음)
     */
    public record Options(boolean noCdc, long maxWaitSeconds) {
    }

    /** 단계 하나의 결과 */
    public record Step(int no, String name, String status, long elapsedMs, String detail) {
    }

    /** @param code 종료 코드 0·{@link #FAILED}·{@link SyncRunner#DRAIN_TIMEOUT} */
    public record Result(long cutoverId, int code, List<Step> steps, long elapsedMs, String error) {
    }

    /** 사람이 고칠 수 있는 이유로 시작하지 않음 */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** 단계 실패. 종료 코드를 함께 싣는다 */
    private static final class StepFailed extends Exception {
        final int code;

        StepFailed(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    private final KdmsConfig cfg;
    private final Rules rules;
    private final SchemaPlan plan;
    private final Connections db;
    private final PrintWriter out;
    private final String configSha256;
    private volatile SyncRunner sync;
    private volatile boolean stop;

    public CutoverRunner(KdmsConfig cfg, Rules rules, SchemaPlan plan, Connections db, PrintWriter out, String configSha256) {
        this.cfg = cfg;
        this.rules = rules;
        this.plan = plan;
        this.db = db;
        this.out = out;
        this.configSha256 = configSha256;
    }

    /** Ctrl+C: 마지막 반영 중이면 그 배치를 마치고 멈춘다. 다음 단계로 가지 않는다 */
    public void requestStop() {
        stop = true;
        SyncRunner s = sync;
        if (s != null) {
            s.requestStop();
        }
    }

    public Result run(Options o) throws SQLException, InterruptedException {
        if (plan.blocked()) {
            throw new Refused("계획에 오류가 있어 전환하지 않는다(kdms plan 보고서의 오류 절)");
        }
        try (Connection state = db.target()) {
            SchemaInstaller.install(state);
            String running = CaptureStore.running(state, cfg.jobName());
            if (running != null) {
                throw new Refused(running + " 이 작업 " + cfg.jobName() + " 을 실행하고 있다. 전환은 그것을 멈춘 뒤에 한다(kdms sync 는 Ctrl+C. "
                        + "마지막 반영은 kdms cutover 가 한다)");
            }
            lock(state);
            long jobId = checkJob(state, o);

            long started = System.currentTimeMillis();
            LocalDateTime srcStart = sourceNow();
            long cutoverId = begin(state, jobId, srcStart);
            out.println("KDMS 전환 · 작업 " + cfg.jobName() + " (job_id " + jobId + ", cutover_id " + cutoverId + ") · "
                    + cfg.source() + " → " + cfg.target());
            out.println("원천 쓰기를 멈춘 상태여야 한다. 시작 시각(원천) " + (srcStart == null ? "-" : srcStart.withNano(0))
                    + " · 여기서부터 끝까지가 예상 다운타임이다");
            out.flush();

            List<Step> steps = new ArrayList<>();
            String error = null;
            int code = 0;
            try {
                // ① 마지막 반영: 캡처 지연을 넘어 원천 마지막 커밋까지 반영하고 쓰기가 없음을 확인(SyncRunner --drain, docs/cdc.md §5)
                steps.add(step(state, cutoverId, 1, "drain", "마지막 반영", () -> {
                    if (o.noCdc()) {
                        return skipped("--no-cdc: 워터마크 없이 적재한 작업이라 반영할 변경이 없다");
                    }
                    SyncRunner r = new SyncRunner(cfg, rules, plan, db, out);
                    sync = r;
                    if (stop) {
                        throw new StepFailed("중지 요청", FAILED);
                    }
                    int c;
                    try {
                        c = r.run(new SyncRunner.Options(true, true, o.maxWaitSeconds()));
                    } catch (SyncRunner.Refused e) {
                        throw new StepFailed(e.getMessage(), FAILED);
                    } finally {
                        sync = null;
                    }
                    if (stop) {
                        throw new StepFailed("중지 요청: 마지막 반영을 멈췄다", FAILED);
                    }
                    if (c == SyncRunner.DRAIN_TIMEOUT) {
                        throw new StepFailed(o.maxWaitSeconds() + "초 안에 따라잡지 못했다. 원천 쓰기를 멈췄는지 확인한다(--max-wait 로 늘릴 수 있다)",
                                SyncRunner.DRAIN_TIMEOUT);
                    }
                    if (c != 0) {
                        throw new StepFailed("마지막 반영 실패(종료 코드 " + c + ", 위 메시지)", FAILED);
                    }
                    return "반영 대기 0, 원천 마지막 커밋까지 반영";
                }));

                // ② PK 없는 테이블: 변경분을 반영하지 않았으므로 쓰기가 멈춘 지금 통째로 다시 적재(plan.md §4.6)
                List<TablePlan> noPk = plan.tables().stream().filter(t -> t.primaryKey() == null).toList();
                steps.add(step(state, cutoverId, 2, "reload", "PK 없는 테이블 재적재", () -> {
                    if (noPk.isEmpty()) {
                        return skipped("PK 없는 테이블 없음");
                    }
                    Set<String> names = noPk.stream().map(t -> Rules.tableKey(t.srcSchema(), t.srcName()))
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
                    Loader.Result r;
                    try {
                        r = new Loader(cfg, rules, plan, db, out, configSha256).run(new Loader.Options(names, true, false, 0, o.noCdc(), true));
                    } catch (Loader.Refused e) {
                        throw new StepFailed(e.getMessage(), FAILED);
                    }
                    if (!r.allLoaded()) {
                        throw new StepFailed("재적재 실패: " + r.tables().stream().filter(t -> "FAILED".equals(t.status()))
                                .map(t -> t.srcTable() + " " + t.error()).collect(Collectors.joining(", ")), FAILED);
                    }
                    return "테이블 " + r.tables().size() + "개, " + String.format("%,d", r.tables().stream().mapToLong(Loader.TableResult::rows).sum()) + "행";
                }));

                // ③ 반영 중 미뤄 둔 UNIQUE·보조 인덱스(docs/cdc.md §4). 이미 있으면 건너뛴다
                steps.add(step(state, cutoverId, 3, "post_load", "UNIQUE·인덱스", () -> ddl(Phase.POST_LOAD)));

                // ④ 검증. 불일치면 여기서 멈춘다(전환하지 않음)
                steps.add(step(state, cutoverId, 4, "verify", "검증", () -> {
                    Verifier.Result r;
                    try {
                        r = new Verifier(cfg, rules, plan, db).run(Set.of(), t -> {
                            out.println("  " + VerifyReport.table(t).replace("\n", "\n  "));
                            out.flush();
                        });
                    } catch (Verifier.Refused e) {
                        throw new StepFailed(e.getMessage(), FAILED);
                    }
                    setVerifyRun(state, cutoverId, r.runId());
                    String line = "검증 항목 " + r.checks() + "개 중 일치 " + (r.checks() - r.mismatches()) + " · 불일치 " + r.mismatches()
                            + " (run_id " + r.runId() + ")";
                    if (r.mismatches() > 0) {
                        throw new StepFailed(line + ". 전환하지 않는다. 차이 행은 kdms.verify_row_diff(run_id " + r.runId() + ")", FAILED);
                    }
                    jobStatus(state, jobId, "VERIFIED", null);
                    return line;
                }));

                // ⑤ IDENTITY·SEQUENCE 다음 값(A09)
                steps.add(step(state, cutoverId, 5, "setval", "IDENTITY·SEQUENCE", () -> {
                    List<SequenceSync.Item> items;
                    try (Connection src = db.source(); Connection tgt = db.target()) {
                        items = SequenceSync.run(plan, src, tgt);
                    }
                    for (SequenceSync.Item i : items) {
                        out.println("  " + i.kind() + " " + i.target() + ": 원천 " + (i.srcValue() == null ? "사용 안 함" : i.srcValue())
                                + " → 대상 다음 값 " + i.next() + (i.note() == null ? "" : " · " + i.note()));
                    }
                    out.flush();
                    return items.isEmpty() ? "IDENTITY·SEQUENCE 없음" : items.stream().map(i -> i.target() + " 다음 " + i.next())
                            .collect(Collectors.joining(", "));
                }));

                // ⑥ FK(NOT VALID 없이 만들어 기존 행도 검사)
                steps.add(step(state, cutoverId, 6, "fk", "FK", () -> ddl(Phase.CUTOVER)));
            } catch (StepFailed e) {
                error = e.getMessage();
                code = e.code;
            }

            long elapsed = System.currentTimeMillis() - started;
            finish(state, cutoverId, jobId, error, elapsed);
            out.println();
            out.println(summary(steps, elapsed, error));
            if (error == null) {
                List<String> manual = manualWork();
                if (!manual.isEmpty()) {
                    out.println();
                    out.println("전환 뒤 사람이 할 일(자동 변환하지 않음, KIS:docs/appcompat.md):");
                    manual.forEach(m -> out.println("  - " + m));
                }
            }
            out.flush();
            return new Result(cutoverId, code, List.copyOf(steps), elapsed, error);
        }
    }

    /** 결과 표(단계·소요 시간·다운타임) */
    static String summary(List<Step> steps, long elapsedMs, String error) {
        StringBuilder b = new StringBuilder(error == null ? "전환 끝" : "전환 멈춤: " + error);
        b.append('\n');
        for (Step s : steps) {
            b.append("  ").append(s.no()).append(' ').append(pad(s.name(), 20)).append(pad(label(s.status()), 8))
                    .append(String.format("%7.1f초  ", s.elapsedMs() / 1000.0)).append(s.detail() == null ? "" : s.detail()).append('\n');
        }
        b.append(String.format("소요 시간(예상 다운타임) %.1f초", elapsedMs / 1000.0));
        if (error != null) {
            b.append("\n작업 상태 FAILED. 원인을 고친 뒤 kdms cutover 를 다시 실행한다(처음 단계부터 다시, 결과는 같다). "
                    + "원천 쓰기를 다시 열어야 하면 kdms sync 로 동기화를 이어 간다");
        }
        return b.toString();
    }

    /** 한글은 터미널에서 두 칸이라 표시 폭으로 맞춘다 */
    static String pad(String s, int width) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            w += (ch >= 0x1100 && (ch <= 0x115F || (ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0x3130 && ch <= 0x318F))) ? 2 : 1;
        }
        return s + " ".repeat(Math.max(1, width - w));
    }

    private static String label(String status) {
        return switch (status) {
            case "DONE" -> "완료";
            case "SKIPPED" -> "건너뜀";
            case "FAILED" -> "실패";
            default -> status;
        };
    }

    /** 원천 트리거·뷰·SP 등 사람이 PG 에 옮길 것(계획 보고서의 목록) */
    List<String> manualWork() {
        List<String> out = new ArrayList<>();
        plan.allIssues().stream().filter(i -> i.message().startsWith("트리거 "))
                .forEach(i -> out.add(i.where() + " " + i.message().substring(0, i.message().indexOf(':') < 0 ? i.message().length()
                        : i.message().indexOf(':')) + " → PL/pgSQL 트리거로 배포(반영이 끝났으므로 지금 켜도 이력이 두 번 생기지 않는다)"));
        if (!plan.manualObjects().isEmpty()) {
            out.add("뷰·SP·함수·SYNONYM 등 " + plan.manualObjects().size() + "개: " + plan.manualObjects().stream()
                    .map(m -> m.schema() + "." + m.name() + "(" + m.typeDesc().toLowerCase(Locale.ROOT) + ")").collect(Collectors.joining(", ")));
        }
        return out;
    }

    private String ddl(Phase phase) throws SQLException, StepFailed {
        try (Connection c = db.target()) {
            SchemaApplier.Result r = SchemaApplier.apply(c, plan, phase, false, new SchemaApplier.Job(cfg.jobName(),
                    cfg.source().host() + ":" + cfg.source().port(), cfg.source().database(), cfg.target().database(), configSha256));
            return "문장 " + r.statements() + "개 적용" + (r.skipped() > 0 ? ", 이미 있어 건너뜀 " + r.skipped() + "개" : "");
        } catch (SchemaApplier.Refused e) {
            throw new StepFailed(e.getMessage(), FAILED);
        } catch (SQLException e) {
            throw new StepFailed(phase.title + " 적용 실패(모두 되돌림): " + SafeMessage.of(e), FAILED);
        }
    }

    @FunctionalInterface
    private interface Body {
        String run() throws SQLException, InterruptedException, StepFailed;
    }

    /** 건너뛴 단계 표시(Body 의 반환값 앞에 붙인다) */
    private static final String SKIP = "\u0000skip:";

    private static String skipped(String why) {
        return SKIP + why;
    }

    /** 단계 하나: 기록 → 실행 → 기록. 실패하면 StepFailed 를 다시 던진다 */
    private Step step(Connection state, long cutoverId, int no, String key, String name, Body body) throws SQLException, StepFailed, InterruptedException {
        if (stop) {
            throw new StepFailed("중지 요청: " + name + " 전에 멈췄다", FAILED);
        }
        out.println();
        out.println("[" + no + "/6] " + name);
        out.flush();
        try (PreparedStatement ps = state.prepareStatement(
                "INSERT INTO kdms.cutover_step (cutover_id, step_no, step, status) VALUES (?, ?, ?, 'RUNNING')")) {
            ps.setLong(1, cutoverId);
            ps.setInt(2, no);
            ps.setString(3, key);
            ps.executeUpdate();
        }
        long t0 = System.currentTimeMillis();
        String status = "DONE";
        String detail;
        StepFailed failed = null;
        try {
            detail = body.run();
            if (detail != null && detail.startsWith(SKIP)) {
                status = "SKIPPED";
                detail = detail.substring(SKIP.length());
            }
        } catch (StepFailed e) {
            status = "FAILED";
            detail = e.getMessage();
            failed = e;
        } catch (SQLException | RuntimeException e) {
            status = "FAILED";
            detail = SafeMessage.of(e);
            failed = new StepFailed(name + " 실패: " + detail, FAILED);
        }
        long ms = System.currentTimeMillis() - t0;
        try (PreparedStatement ps = state.prepareStatement("""
                UPDATE kdms.cutover_step SET status = ?, finished_at = now(), elapsed_ms = ?, detail = ?
                WHERE cutover_id = ? AND step_no = ?""")) {
            ps.setString(1, status);
            ps.setLong(2, ms);
            ps.setString(3, cut(detail));
            ps.setLong(4, cutoverId);
            ps.setInt(5, no);
            ps.executeUpdate();
        }
        out.println("  → " + label(status) + String.format(" (%.1f초)", ms / 1000.0) + (detail == null ? "" : ": " + detail));
        out.flush();
        if (failed != null) {
            throw failed;
        }
        return new Step(no, name, status, ms, detail);
    }

    private static String cut(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private LocalDateTime sourceNow() {
        try (Connection src = db.source()) {
            return SourceCdc.now(src);
        } catch (SQLException e) {
            throw new Refused("원천에 접속하지 못했다: " + SafeMessage.of(e));
        }
    }

    private void lock(Connection state) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement("SELECT pg_try_advisory_lock(hashtext('kdms.cutover:' || ?))")) {
            ps.setString(1, cfg.jobName());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new Refused("다른 kdms cutover 가 작업 " + cfg.jobName() + " 을 전환하고 있다");
                }
            }
        }
    }

    /** 작업 상태·테이블 적재·워터마크를 확인한다 */
    private long checkJob(Connection state, Options o) throws SQLException {
        long jobId;
        String status;
        try (PreparedStatement ps = state.prepareStatement("SELECT job_id, status FROM kdms.job WHERE job_name = ?")) {
            ps.setString(1, cfg.jobName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Refused("작업 " + cfg.jobName() + " 이 없다. kdms schema → kdms sync → kdms load 를 먼저 한다");
                }
                jobId = rs.getLong(1);
                status = rs.getString(2);
            }
        }
        if ("DONE".equals(status)) {
            throw new Refused("작업 " + cfg.jobName() + " 은 이미 전환이 끝났다(DONE). 처음부터 다시 하려면 kdms reset --yes");
        }
        if (!STARTABLE.contains(status)) {
            throw new Refused("작업 " + cfg.jobName() + " 은 " + status + " 단계다. 전환은 전체 적재가 끝나고 kdms sync 로 따라잡은 뒤에 한다");
        }
        List<String> notLoaded = new ArrayList<>();
        try (PreparedStatement ps = state.prepareStatement("""
                SELECT src_schema || '.' || src_table || ' ' || status FROM kdms.job_table
                WHERE job_id = ? AND status NOT IN ('LOADED', 'EXCLUDED') ORDER BY job_table_id""")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notLoaded.add(rs.getString(1));
                }
            }
        }
        if (!notLoaded.isEmpty()) {
            throw new Refused("전체 적재가 끝나지 않은 테이블이 있다: " + String.join(", ", notLoaded) + ". kdms load 를 끝낸 뒤 전환한다");
        }
        CaptureStore.Watermark wm = CaptureStore.watermark(state, jobId);
        if (wm == null && !o.noCdc()) {
            throw new Refused("워터마크가 없다(변경분 동기화를 하지 않은 작업). 원천 쓰기 없이 kdms load --no-cdc 로 적재했으면 kdms cutover --no-cdc");
        }
        if (wm != null && o.noCdc()) {
            throw new Refused("워터마크가 있는 작업(변경분 동기화 중)에는 --no-cdc 를 쓸 수 없다. 마지막 반영을 건너뛰면 변경이 빠진다");
        }
        return jobId;
    }

    private long begin(Connection state, long jobId, LocalDateTime srcStart) throws SQLException {
        long id;
        try (PreparedStatement ps = state.prepareStatement(
                "INSERT INTO kdms.cutover_run (job_id, src_started_at) VALUES (?, ?) RETURNING cutover_id")) {
            ps.setLong(1, jobId);
            ps.setObject(2, srcStart);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        jobStatus(state, jobId, "CUTOVER", null);
        log(state, jobId, "INFO", "cutover " + id + " 시작");
        return id;
    }

    private void finish(Connection state, long cutoverId, long jobId, String error, long elapsedMs) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement("""
                UPDATE kdms.cutover_run SET status = ?, finished_at = now(), elapsed_ms = ?, last_error = ? WHERE cutover_id = ?""")) {
            ps.setString(1, error == null ? "DONE" : "FAILED");
            ps.setLong(2, elapsedMs);
            ps.setString(3, cut(error));
            ps.setLong(4, cutoverId);
            ps.executeUpdate();
        }
        jobStatus(state, jobId, error == null ? "DONE" : "FAILED", error == null ? null : cut("전환 멈춤: " + error));
        log(state, jobId, error == null ? "INFO" : "ERROR", "cutover " + cutoverId + (error == null ? " 끝" : " 멈춤: " + error)
                + String.format(", %.1f초", elapsedMs / 1000.0));
    }

    private static void setVerifyRun(Connection state, long cutoverId, long runId) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement("UPDATE kdms.cutover_run SET verify_run_id = ? WHERE cutover_id = ?")) {
            ps.setLong(1, runId);
            ps.setLong(2, cutoverId);
            ps.executeUpdate();
        }
    }

    private static void jobStatus(Connection state, long jobId, String status, String error) throws SQLException {
        try (PreparedStatement ps = state.prepareStatement(
                "UPDATE kdms.job SET status = ?, last_error = ?, updated_at = now() WHERE job_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setLong(3, jobId);
            ps.executeUpdate();
        }
    }

    private static void log(Connection c, long jobId, String level, String message) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kdms.event_log (job_id, level, stage, message) VALUES (?, ?, 'cutover', ?)")) {
            ps.setLong(1, jobId);
            ps.setString(2, level);
            ps.setString(3, cut(message));
            ps.executeUpdate();
        }
    }
}
