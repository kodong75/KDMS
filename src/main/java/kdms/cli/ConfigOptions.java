package kdms.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

    /** 같은 설정으로 다른 명령을 부를 때 넘길 옵션(웹 화면이 명령을 실행할 때) */
    public java.util.List<String> args() {
        return java.util.List.of("-c", config.toString(), "--env-file", envFile.toString());
    }

    public KdmsConfig loadConfig() {
        return new ConfigLoader(EnvResolver.system(envFile)).load(config);
    }

    public Rules loadRules(KdmsConfig cfg) {
        return RulesLoader.load(cfg.rules().isBlank() ? null : Path.of(cfg.rules()));
    }

    /** 설정 파일 + 작업별 규칙 파일 내용의 SHA-256(kdms.job.config_sha256). 바뀌면 재시작 때 알아챈다 */
    public String configSha256(KdmsConfig cfg) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(Files.readAllBytes(config));
            if (!cfg.rules().isBlank()) {
                d.update(Files.readAllBytes(Path.of(cfg.rules())));
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("설정 파일 해시를 만들지 못했다", e);
        }
    }
}
