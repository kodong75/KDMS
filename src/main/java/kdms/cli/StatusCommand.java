package kdms.cli;

import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

import kdms.catalog.ProbeResult;
import kdms.catalog.StatusReport;
import kdms.config.KdmsConfig;
import kdms.rules.Rules;
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
        print(report, spec.commandLine().getOut());
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
