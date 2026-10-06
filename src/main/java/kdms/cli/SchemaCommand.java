package kdms.cli;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.Callable;

import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.ddl.DdlWriter.Phase;
import kdms.ddl.SchemaApplier;
import kdms.ddl.SchemaPlan;
import kdms.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * plan 과 같은 계획을 만들어 대상 PG 에 적용한다(한 트랜잭션). 적재 전 단계면 작업·테이블을 kdms 관리 테이블에 등록한다.
 * 종료 코드: 0 적용, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 적용 거부(이미 있는 테이블 등).
 */
@Command(name = "schema", mixinStandardHelpOptions = true,
        description = "계획한 DDL 을 대상 PG 에 적용한다. 기본은 적재 전 단계(테이블·PK). 이미 있는 테이블은 --replace 없이는 건드리지 않는다.")
public class SchemaCommand implements Callable<Integer> {

    public static final int REFUSED = 4;

    @Mixin
    ConfigOptions options;

    @Mixin
    PlanSupport support;

    @Option(names = "--phase", paramLabel = "단계", defaultValue = "pre-load",
            description = "pre-load(테이블·PK) | post-load(UNIQUE·인덱스, 적재 뒤) | cutover(FK, 전환 때) (기본값: ${DEFAULT-VALUE})")
    String phase;

    @Option(names = "--replace", description = "대상에 같은 테이블·시퀀스가 있으면 지우고 다시 만든다(그 안의 데이터도 지워진다). 적재가 시작된 작업에는 쓸 수 없다")
    boolean replace;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        Phase p = switch (phase.toLowerCase(Locale.ROOT)) {
            case "pre-load" -> Phase.PRE_LOAD;
            case "post-load" -> Phase.POST_LOAD;
            case "cutover" -> Phase.CUTOVER;
            default -> throw new CommandLine.ParameterException(spec.commandLine(),
                    "--phase 는 pre-load | post-load | cutover (값: " + phase + ")");
        };
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        SchemaPlan plan;
        try {
            plan = support.plan(cfg, rules);
        } catch (SQLException e) {
            err.println("원천 접속·조회 실패 (" + cfg.source() + "): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        String report = support.write(plan, cfg, rules, out);
        if (plan.blocked()) {
            out.print(report);
            err.println("계획에 오류가 있어 대상에 적용하지 않았다(보고서의 오류 절)");
            return PlanSupport.BLOCKED;
        }
        SchemaApplier.Job job = new SchemaApplier.Job(cfg.jobName(), cfg.source().host() + ":" + cfg.source().port(),
                cfg.source().database(), cfg.target().database(), options.configSha256(cfg));
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            SchemaApplier.Result r = SchemaApplier.apply(c, plan, p, replace, job);
            out.println("대상 " + cfg.target() + " 에 " + phase + " 적용: 문장 " + r.statements() + "개, 테이블 "
                    + plan.tables().size() + "개" + (r.dropped().isEmpty() ? "" : ", 지우고 다시 만든 것: " + String.join(", ", r.dropped()))
                    + (r.skipped() > 0 ? ", 이미 있어 건너뜀 " + r.skipped() + "개" : ""));
            if (r.jobId() > 0) {
                out.println("작업 " + cfg.jobName() + " (job_id " + r.jobId() + ")" + (p == Phase.PRE_LOAD ? " 상태 SCHEMA_DONE" : ""));
            }
        } catch (SchemaApplier.Refused e) {
            err.println("적용하지 않음: " + e.getMessage());
            return REFUSED;
        } catch (SQLException e) {
            err.println("대상 적용 실패(모두 되돌림): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        out.flush();
        return 0;
    }
}
