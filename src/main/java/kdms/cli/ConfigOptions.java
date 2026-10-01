package kdms.cli;

import java.nio.file.Path;

import kdms.config.ConfigLoader;
import kdms.config.EnvResolver;
import kdms.config.KdmsConfig;
import kdms.rules.Rules;
import kdms.rules.RulesLoader;
import picocli.CommandLine.Option;

/** 명령마다 공통인 설정 파일 옵션. */
public class ConfigOptions {

    @Option(names = {"-c", "--config"}, paramLabel = "파일", defaultValue = "config/kdms.yml",
            description = "작업 설정 파일 (기본값: ${DEFAULT-VALUE})")
    Path config;

    @Option(names = "--env-file", paramLabel = "파일", defaultValue = ".env",
            description = "비밀번호 등을 읽을 .env 파일. 환경 변수가 먼저다 (기본값: ${DEFAULT-VALUE})")
    Path envFile;

    public KdmsConfig loadConfig() {
        return new ConfigLoader(EnvResolver.system(envFile)).load(config);
    }

    public Rules loadRules(KdmsConfig cfg) {
        return RulesLoader.load(cfg.rules().isBlank() ? null : Path.of(cfg.rules()));
    }
}
