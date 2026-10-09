package kdms.cli;

import java.util.concurrent.Callable;

import org.springframework.boot.builder.SpringApplicationBuilder;

import kdms.config.KdmsConfig;
import kdms.rules.Rules;
import kdms.web.KdmsWebApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** 내장 웹 화면을 띄운다. 기본 주소는 http://127.0.0.1:8080 (설정 web.address / web.port). */
@Command(name = "web", mixinStandardHelpOptions = true,
        description = "내장 웹 화면을 띄운다(기본 127.0.0.1:8080). Ctrl+C 로 끝낸다.")
public class WebCommand implements Callable<Integer> {

    /** main 이 System.exit 하지 않고 웹 서버 스레드를 살려 두라는 표시. */
    public static final int RUNNING = 100;

    @Mixin
    ConfigOptions options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        if (!cfg.web().isLoopbackOnly()) {
            spec.commandLine().getErr().println("주의: 웹 화면을 " + cfg.web().address()
                    + " 에 엽니다. 인증이 없으므로 신뢰하는 망에서만 쓴다.");
        }
        start(cfg, rules, options.args());
        spec.commandLine().getOut().println("웹 화면: http://" + cfg.web().address() + ":" + cfg.web().port() + "/");
        return RUNNING;
    }

    /** 시험용: 화면에서 명령을 실행하지 않는다 */
    public static org.springframework.context.ConfigurableApplicationContext start(KdmsConfig cfg, Rules rules) {
        return start(cfg, rules, null);
    }

    /** @param cliArgs 화면 버튼이 명령을 실행할 때 붙일 설정 옵션. null 이면 버튼을 쓰지 않는다 */
    public static org.springframework.context.ConfigurableApplicationContext start(KdmsConfig cfg, Rules rules, java.util.List<String> cliArgs) {
        // 기본 속성(properties)은 application.yml 보다 우선순위가 낮아 web.port 가 무시됐다 → 명령줄 인자로 넘긴다
        return new SpringApplicationBuilder(KdmsWebApplication.class)
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("kdmsConfig", cfg);
                    ctx.getBeanFactory().registerSingleton("kdmsRulesSummary",
                            new KdmsWebApplication.RulesSummary(StatusCommand.rulesSummary(cfg, rules)));
                    ctx.getBeanFactory().registerSingleton("kdmsTasks", new kdms.web.TaskService(cliArgs));
                })
                .run("--server.address=" + cfg.web().address(), "--server.port=" + cfg.web().port());
    }
}
