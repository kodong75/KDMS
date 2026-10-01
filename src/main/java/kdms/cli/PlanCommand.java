package kdms.cli;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.concurrent.Callable;

import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan;
import kdms.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * 원천 카탈로그를 읽어 변환 규칙을 적용한 계획 보고서와 대상 DDL 을 만든다. 원천은 읽기만, 대상에는 접속하지 않는다.
 * 종료 코드: 0 통과, 1 설정 오류, 2 원천 접속 실패, 3 계획에 오류(규칙 파일을 고친다).
 */
@Command(name = "plan", mixinStandardHelpOptions = true,
        description = "원천 테이블·컬럼에 변환 규칙을 적용해 보고서와 대상 DDL 을 만든다. 아무 DB 도 바꾸지 않는다.")
public class PlanCommand implements Callable<Integer> {

    @Mixin
    ConfigOptions options;

    @Mixin
    PlanSupport support;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        PrintWriter out = spec.commandLine().getOut();
        SchemaPlan plan;
        try {
            plan = support.plan(cfg, rules);
        } catch (SQLException e) {
            spec.commandLine().getErr().println("원천 접속·조회 실패 (" + cfg.source() + "): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        out.print(support.write(plan, cfg, rules, out));
        out.flush();
        return plan.blocked() ? PlanSupport.BLOCKED : 0;
    }
}
