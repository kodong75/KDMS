package kdms.cli;

import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

import kdms.catalog.ProbeResult;
import kdms.catalog.StatusReport;
import kdms.config.KdmsConfig;
import kdms.rules.Rules;
import kdms.state.JobView;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * 원천·대상 접속과 버전, 이관 준비 상태를 출력한다. 읽기만 한다.
 * 종료 코드: 0 둘 다 접속, 1 설정 오류, 2 접속 실패.
 */
@Command(name = "status", mixinStandardHelpOptions = true,
        description = "원천(MS-SQL)·대상(PG) 접속 확인과 버전·CDC·관리 스키마 상태를 출력한다. 아무것도 바꾸지 않는다.")
public class StatusCommand implements Callable<Integer> {

    public static final int CONNECTION_FAILED = 2;

    @Mixin
    ConfigOptions options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        StatusReport report = StatusReport.collect(VersionProvider.version(), cfg, rulesSummary(cfg, rules));
        PrintWriter out = spec.commandLine().getOut();
        print(report, out);
        if (report.target().connected()) {
            try (java.sql.Connection c = kdms.config.Jdbc.openTarget(cfg.target())) {
                printJob(JobView.read(c, cfg.jobName(), 0), kdms.cdc.CaptureStore.runningNow(c, cfg.jobName()), out);
            } catch (java.sql.SQLException e) {
                out.println();
                out.println("[작업] 조회 실패: " + e.getMessage());
            }
            out.flush();
        }
        return report.allConnected() ? 0 : CONNECTION_FAILED;
    }

    static String rulesSummary(KdmsConfig cfg, Rules rules) {
        String file = cfg.rules().isBlank() ? "기본(jar 안)" : "기본 + " + cfg.rules();
        return file + ", 타입 규칙 " + rules.types().size() + "개";
    }

    static void print(StatusReport r, PrintWriter out) {
        out.println("KDMS " + r.kdmsVersion() + "  작업 " + r.jobName() + "  "
                + r.checkedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")));
        out.println("변환 규칙: " + r.rulesSummary());
        printProbe(r.source(), out);
        printProbe(r.target(), out);
        out.flush();
    }

    /** 작업 진행(적재·반영·검증·전환). 웹 화면과 같은 값(kdms.state.JobView) */
    static void printJob(JobView v, java.util.List<String> running, PrintWriter out) {
        out.println();
        if (v.job() == null) {
            out.println("[작업] 없음 (kdms schema 로 만든다)");
            return;
        }
        out.println("[작업] " + v.job().name() + " (job_id " + v.job().id() + ")");
        out.println(pad("상태") + v.job().status() + (running.isEmpty() ? "" : " · 실행 중: " + String.join(", ", running.stream().map(r -> "kdms " + r).toList())));
        if (v.job().lastError() != null) {
            out.println(pad("마지막 오류") + v.job().lastError());
        }
        long loaded = v.tables().stream().filter(t -> "LOADED".equals(t.status()) || "EXCLUDED".equals(t.status())).count();
        out.println(pad("전체 적재") + "테이블 " + v.tables().size() + "개 중 " + loaded + "개 끝, 적재한 행 "
                + String.format("%,d", v.tables().stream().mapToLong(t -> t.rowsLoaded() == null ? 0 : t.rowsLoaded()).sum()));
        JobView.Sync s = v.sync();
        if (s == null) {
            out.println(pad("변경분 반영") + "워터마크 없음");
        } else {
            out.println(pad("변경분 반영") + "수집 " + String.format("%,d", s.captured()) + " · 반영 " + String.format("%,d", s.applied())
                    + " · 대기 " + (s.pending() == null ? "-" : String.format("%,d", s.pending()))
                    + " · 지연 " + (s.lagSeconds() == null ? "-" : s.lagSeconds().setScale(1, java.math.RoundingMode.HALF_UP) + "초")
                    + (s.statusAt() == null ? "" : " (" + s.statusAt().atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0) + " 기준)"));
        }
        JobView.Verify vf = v.verify();
        out.println(pad("검증") + (vf == null ? "안 함" : "run " + vf.runId() + ": " + (vf.checks() == null ? "진행 중"
                : "항목 " + vf.checks() + "개 중 일치 " + (vf.checks() - vf.mismatches()) + " · 불일치 " + vf.mismatches())));
        JobView.Cutover co = v.cutover();
        if (co != null) {
            out.println(pad("전환") + "cutover " + co.id() + ": " + co.status()
                    + (co.elapsedMs() == null ? "" : String.format(" · 소요 %.1f초", co.elapsedMs() / 1000.0))
                    + (co.lastError() == null ? "" : " · " + co.lastError()));
        }
    }

    private static void printProbe(ProbeResult p, PrintWriter out) {
        out.println();
        out.println("[" + p.role() + "] " + p.target());
        if (!p.connected()) {
            out.println(pad("접속") + "실패 (" + p.elapsedMs() + " ms)");
            out.println(pad("오류") + p.error());
            return;
        }
        out.println(pad("접속") + "OK (" + p.elapsedMs() + " ms)");
        for (ProbeResult.Item i : p.items()) {
            out.println(pad(i.name()) + i.value());
        }
        for (String w : p.warnings()) {
            out.println(pad("주의") + w);
        }
    }

    /** 한글은 터미널에서 두 칸을 차지하므로 표시 폭으로 맞춘다. */
    static String pad(String name) {
        int width = 0;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            width += (ch >= 0x1100 && (ch <= 0x115F || (ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0x3130 && ch <= 0x318F))) ? 2 : 1;
        }
        return "  " + name + " ".repeat(Math.max(1, 20 - width));
    }
}
