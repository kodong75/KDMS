package kdms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static final Map<String, String> ENV = Map.of(
            "KDMS_SRC_HOST", "192.168.0.12",
            "KDMS_SRC_USER", "kodong_ms",
            "KDMS_SRC_PASSWORD", "s3cr#t: x",
            "KDMS_SRC_TRUST_CERT", "true",
            "KDMS_TGT_HOST", "192.168.0.12",
            "KDMS_TGT_PASSWORD", "pg-secret");

    private final ConfigLoader loader = new ConfigLoader(new EnvResolver(k -> null, ENV));

    @Test
    void 저장소의_예시_설정을_읽는다() {
        KdmsConfig c = loader.load(Path.of("config/kdms.example.yml"));
        assertThat(c.jobName()).isEqualTo("kdms_mock");
        assertThat(c.source().host()).isEqualTo("192.168.0.12");
        assertThat(c.source().port()).isEqualTo(1433);
        assertThat(c.source().database()).isEqualTo("KDMS_MOCK");
        assertThat(c.source().password()).isEqualTo("s3cr#t: x"); // YAML 특수문자가 깨지지 않는다
        assertThat(c.source().properties()).containsEntry("trustServerCertificate", "true").containsEntry("encrypt", "true");
        assertThat(c.target().database()).isEqualTo("kdms");
        assertThat(c.target().user()).isEqualTo("kdms_app");
        assertThat(c.target().properties()).containsEntry("sslmode", "prefer");
        assertThat(c.load().tableParallelism()).isEqualTo(4);
        assertThat(c.load().isolation()).isEqualTo("snapshot");
        assertThat(c.tables().include()).containsExactly("dbo.*");
        assertThat(c.rules()).isEqualTo("config/kdms-rules.yml");
        assertThat(c.web().address()).isEqualTo("127.0.0.1");
        assertThat(c.web().isLoopbackOnly()).isTrue();
    }

    @Test
    void 비밀번호는_문자열표현과_URL에_나오지_않는다() {
        KdmsConfig c = loader.load(Path.of("config/kdms.example.yml"));
        assertThat(c.source().toString()).doesNotContain("s3cr").isEqualTo("kodong_ms@192.168.0.12:1433/KDMS_MOCK");
        assertThat(c.toString()).doesNotContain("s3cr").doesNotContain("pg-secret");
        assertThat(Jdbc.sourceUrl(c.source())).doesNotContain("s3cr");
        assertThat(Jdbc.targetUrl(c.target())).isEqualTo("jdbc:postgresql://192.168.0.12:5432/kdms");
    }

    @Test
    void 알수없는_키는_오류(@TempDir Path dir) throws IOException {
        Path f = write(dir, """
                source: { host: h, database: d, user: u, pasword: oops }
                target: { host: h, database: d, user: u }
                """);
        assertThatThrownBy(() -> loader.load(f)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("source").hasMessageContaining("pasword");
    }

    @Test
    void 숫자_검사(@TempDir Path dir) throws IOException {
        Path f = write(dir, """
                source: { host: h, port: abc, database: d, user: u }
                target: { host: h, database: d, user: u }
                """);
        assertThatThrownBy(() -> loader.load(f)).hasMessageContaining("source.port").hasMessageContaining("양의 정수");
    }

    @Test
    void 필수값_누락(@TempDir Path dir) throws IOException {
        Path f = write(dir, """
                source: { host: h, database: d, user: u, password: p }
                """);
        assertThatThrownBy(() -> loader.load(f)).hasMessageContaining("target");
    }

    @Test
    void 빈_비밀번호는_접속_전에_설정_오류로_알린다(@TempDir Path dir) throws IOException {
        // .env 에 KDMS_SRC_PASSWORD= 만 있고 값이 없는 경우(.env.example 을 복사만 한 상태)
        ConfigLoader blank = new ConfigLoader(new EnvResolver(k -> null, Map.of("KDMS_SRC_PASSWORD", "", "KDMS_TGT_PASSWORD", "x")));
        Path f = write(dir, """
                source: { host: h, database: d, user: u, password: "${KDMS_SRC_PASSWORD}" }
                target: { host: h, database: d, user: u, password: "${KDMS_TGT_PASSWORD}" }
                """);
        assertThatThrownBy(() -> blank.load(f)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("source.password").hasMessageContaining("KDMS_SRC_PASSWORD=");

        Path g = write(dir, """
                source: { host: h, database: d, user: u, password: p }
                target: { host: h, database: d, user: u }
                """);
        assertThatThrownBy(() -> loader.load(g)).hasMessageContaining("target.password").hasMessageContaining("비어 있습니다");
    }

    @Test
    void 원천_읽기_격리_수준(@TempDir Path dir) throws IOException {
        Path f = write(dir, """
                source: { host: h, database: d, user: u, password: p }
                target: { host: h, database: d, user: u, password: p }
                load: { isolation: read_committed }
                """);
        assertThat(loader.load(f).load().isolation()).isEqualTo("read_committed");
        Path g = write(dir, """
                source: { host: h, database: d, user: u, password: p }
                target: { host: h, database: d, user: u, password: p }
                load: { isolation: dirty }
                """);
        assertThatThrownBy(() -> loader.load(g)).hasMessageContaining("load.isolation").hasMessageContaining("snapshot | read_committed");
    }

    @Test
    void 설정파일이_없으면_예시를_안내() {
        assertThatThrownBy(() -> loader.load(Path.of("config/missing.yml")))
                .hasMessageContaining("kdms.example.yml");
    }

    private static Path write(Path dir, String yaml) throws IOException {
        Path f = dir.resolve("kdms.yml");
        Files.writeString(f, yaml);
        return f;
    }
}
