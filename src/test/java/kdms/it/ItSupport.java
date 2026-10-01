package kdms.it;

import java.nio.file.Path;

import kdms.config.ConfigLoader;
import kdms.config.EnvResolver;
import kdms.config.KdmsConfig;

/**
 * 통합 시험 공통. Mac 에서 저장소 루트의 config/kdms.yml + .env 로 노트북 DB 에 붙는다.
 * 다른 파일을 쓰려면 -Dkdms.config=… -Dkdms.env=…
 */
public final class ItSupport {

    private ItSupport() {
    }

    public static KdmsConfig config() {
        Path cfg = Path.of(System.getProperty("kdms.config", "config/kdms.yml"));
        Path env = Path.of(System.getProperty("kdms.env", ".env"));
        return new ConfigLoader(EnvResolver.system(env)).load(cfg);
    }
}
