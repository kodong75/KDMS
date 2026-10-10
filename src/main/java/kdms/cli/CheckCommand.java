package kdms.cli;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.cutover.AfterCutoverCheck;
import kdms.cutover.AfterCutoverCheck.Level;
import kdms.ddl.SchemaPlan;
import kdms.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 전환 뒤 점검(docs/runbook.md §7). 작업·전환 기록, IDENTITY·SEQUENCE 다음 값, 제약·인덱스, 계산 컬럼, (--probe) 입력 시험.
 * 종료 코드: 0 실패 없음, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 5 실패 있음.
 */
@Command(name = "check", mixinStandardHelpOptions = true,
        description = "전환 뒤 대상이 앱을 받을 준비가 됐는지 점검한다: 전환·검증 기록, IDENTITY·SEQUENCE 다음 값, 제약·인덱스, 계산 컬럼. 데이터는 바꾸지 않는다.")
public class CheckCommand implements Callable<Integer> {

    public static final int FAILED = 5;

    @Mixin
    ConfigOptions options;

    @Option(names = "--probe", description = "대소문자만 다른 값·없는 부모를 실제로 넣어 보고 막히는지 본다. 넣은 행은 모두 되돌린다(ROLLBACK), 시퀀스는 쓰지 않는다")
    boolean probe;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        SchemaPlan plan;
        try {
            plan = new PlanSupport().plan(cfg, rules);
        } catch (SQLException e) {
            err.println("원천 접속·조회 실패 (" + cfg.source() + "): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        if (plan.blocked()) {
            err.println("계획에 오류가 있어 점검하지 않는다. kdms plan 보고서의 오류 절을 본다");
            return PlanSupport.BLOCKED;
        }
        out.println("KDMS " + VersionProvider.version() + " 전환 뒤 점검 · 작업 " + cfg.jobName() + " · " + cfg.source() + " → " + cfg.target()
                + " · " + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")));
        out.println(probe ? "입력 시험 포함(--probe): 대상에 행을 넣어 보고 모두 되돌린다. 시퀀스는 쓰지 않는다"
                : "읽기만 한다. 대소문자·FK 입력 시험까지 하려면 --probe");
        out.flush();
        String[] group = {null};
        AfterCutoverCheck.Result r;
        try {
            r = new AfterCutoverCheck(cfg, plan, Connections.of(cfg)).run(probe, i -> {
                if (!i.group().equals(group[0])) {
                    group[0] = i.group();
                    out.println();
                    out.println("[" + i.group() + "]");
                }
                out.println("  " + pad(i.level().label) + i.what() + (i.detail() == null ? "" : ": " + i.detail()));
                out.flush();
            });
        } catch (SQLException e) {
            err.println("접속·조회 실패: " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        out.println();
        out.println("결과: 점검 " + r.items().size() + "개 · 통과 " + r.count(Level.PASS) + " · 실패 " + r.count(Level.FAIL) + " · 경고 "
                + r.count(Level.WARN) + " · 건너뜀 " + r.count(Level.SKIP));
        out.println(r.ok() ? "실패 없음. 경고(사람이 할 일)를 처리한 뒤 앱 접속을 대상으로 돌린다(docs/runbook.md §7)"
                : "실패가 있다. 앱을 대상으로 돌리지 않는다. 각 줄의 안내대로 고친 뒤 kdms check 를 다시 한다");
        out.flush();
        return r.ok() ? 0 : FAILED;
    }

    /** 한글 두 글자(4칸) 이름을 6칸에 맞춘다 */
    private static String pad(String label) {
        return label + " ".repeat(Math.max(1, 8 - label.length() * 2));
    }
}
