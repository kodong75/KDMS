package kdms.cli;

import java.sql.Connection;
import java.util.concurrent.Callable;

import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.state.SchemaInstaller;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** 대상 PG 에 관리 스키마(kdms)를 만들거나 최신 버전으로 올린다. 재실행 안전. */
@Command(name = "init", mixinStandardHelpOptions = true,
        description = "대상 PG 에 관리 스키마 kdms 를 만든다(이미 있으면 빠진 버전만 적용).")
public class InitCommand implements Callable<Integer> {

    @Mixin
    ConfigOptions options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        KdmsConfig cfg = options.loadConfig();
        options.loadRules(cfg); // 규칙 파일 오류도 먼저 잡는다
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            SchemaInstaller.Installed before = SchemaInstaller.installedVersion(c);
            int after = SchemaInstaller.install(c);
            spec.commandLine().getOut().println("관리 스키마 kdms: "
                    + (before == null ? "없음" : "버전 " + before.version()) + " → 버전 " + after
                    + " (" + cfg.target() + ")");
        }
        return 0;
    }
}
