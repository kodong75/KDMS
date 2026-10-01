package kdms.cli;

import java.util.Map;
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
        start(cfg, rules);
        spec.commandLine().getOut().println("웹 화면: http://" + cfg.web().address() + ":" + cfg.web().port() + "/");
        return RUNNING;
    }

    public static org.springframework.context.ConfigurableApplicationContext start(KdmsConfig cfg, Rules rules) {
        return new SpringApplicationBuilder(KdmsWebApplication.class)
                .properties(Map.of(
                        "server.address", cfg.web().address(),
                        "server.port", String.valueOf(cfg.web().port())))
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("kdmsConfig", cfg);
                    ctx.getBeanFactory().registerSingleton("kdmsRulesSummary",
                            new KdmsWebApplication.RulesSummary(StatusCommand.rulesSummary(cfg, rules)));
                })
                .run();
    }
}
