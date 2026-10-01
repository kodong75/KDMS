package kdms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EnvResolverTest {

    private final EnvResolver env = new EnvResolver(
            Map.of("FROM_ENV", "env", "BOTH", "env-wins")::get,
            Map.of("FROM_FILE", "file", "BOTH", "file-loses", "EMPTY", ""));

    @Test
    void 환경변수가_env파일보다_먼저() {
        assertThat(env.resolve("${BOTH}", "k")).isEqualTo("env-wins");
        assertThat(env.resolve("${FROM_FILE}", "k")).isEqualTo("file");
        assertThat(env.resolve("a-${FROM_ENV}-b", "k")).isEqualTo("a-env-b");
    }

    @Test
    void 기본값과_빈값() {
        assertThat(env.resolve("${NONE:1433}", "k")).isEqualTo("1433");
        assertThat(env.resolve("${NONE:}", "k")).isEmpty();
        assertThat(env.resolve("${EMPTY:x}", "k")).isEmpty(); // 빈 값도 "있음"
    }

    @Test
    void 없는_변수는_키_이름만_알리고_멈춘다() {
        assertThatThrownBy(() -> env.resolve("${KDMS_SRC_PASSWORD}", "source.password"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("source.password")
                .hasMessageContaining("KDMS_SRC_PASSWORD");
    }

    @Test
    void 값의_특수문자는_그대로() {
        EnvResolver e = new EnvResolver(Map.of("P", "a$b\\c${x}#:")::get, Map.of());
        assertThat(e.resolve("${P}", "k")).isEqualTo("a$b\\c${x}#:");
    }

    @Test
    void env파일_해석() {
        Map<String, String> m = EnvResolver.parseDotEnv("""
                # 주석
                KDMS_SRC_HOST=192.168.0.12

                export KDMS_TGT_DB=kdms
                QUOTED="a b"
                SINGLE='c=d'
                EMPTY=
                잘못된줄
                """);
        assertThat(m).containsEntry("KDMS_SRC_HOST", "192.168.0.12")
                .containsEntry("KDMS_TGT_DB", "kdms")
                .containsEntry("QUOTED", "a b")
                .containsEntry("SINGLE", "c=d")
                .containsEntry("EMPTY", "")
                .hasSize(5);
    }
}
