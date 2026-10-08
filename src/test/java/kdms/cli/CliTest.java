package kdms.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kdms.Kdms;
import picocli.CommandLine;

class CliTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine cl = Kdms.newCommandLine();
        cl.setOut(new PrintWriter(out));
        cl.setErr(new PrintWriter(err));
        return cl.execute(args);
    }

    @Test
    void 도움말에_명령이_보인다() {
        assertThat(run("--help")).isZero();
        assertThat(out.toString()).contains("status").contains("init").contains("plan").contains("schema")
                .contains("load").contains("verify").contains("cutover").contains("web");
    }

    @Test
    void cutover_는_yes_없이_거부_종료코드_4() {
        assertThat(run("cutover", "-c", "config/kdms.example.yml")).isEqualTo(SchemaCommand.REFUSED);
        assertThat(err.toString()).contains("원천 앱 쓰기를 멈춘 뒤").contains("--yes");
    }

    @Test
    void 빈_비밀번호는_접속하지_않고_종료코드_1(@TempDir Path dir) throws IOException {
        // .env.example 을 복사만 하고 비밀번호를 안 채운 상태
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                KDMS_SRC_HOST=127.0.0.1
                KDMS_SRC_USER=u
                KDMS_SRC_PASSWORD=
                KDMS_TGT_HOST=127.0.0.1
                KDMS_TGT_PASSWORD=
                """);
        for (String cmd : new String[] {"status", "load", "verify"}) {
            err.getBuffer().setLength(0);
            assertThat(run(cmd, "-c", "config/kdms.example.yml", "--env-file", env.toString())).as(cmd).isEqualTo(ErrorHandler.CONFIG_ERROR);
            assertThat(err.toString()).as(cmd).startsWith("설정 오류: source.password: 비밀번호가 비어 있습니다").contains("KDMS_SRC_PASSWORD=");
        }
    }

    @Test
    void load_verify_는_원천에_못_붙으면_종료코드_2(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                KDMS_SRC_HOST=127.0.0.1
                KDMS_SRC_PORT=1
                KDMS_SRC_USER=u
                KDMS_SRC_PASSWORD=never-printed
                KDMS_TGT_HOST=127.0.0.1
                KDMS_TGT_PASSWORD=never-printed
                """);
        for (String cmd : new String[] {"load", "verify"}) {
            assertThat(run(cmd, "-c", "config/kdms.example.yml", "--env-file", env.toString())).as(cmd).isEqualTo(StatusCommand.CONNECTION_FAILED);
        }
        assertThat(err.toString()).contains("원천 접속·조회 실패").doesNotContain("never-printed");
    }

    @Test
    void 설정파일이_없으면_한줄_오류와_종료코드_1() {
        assertThat(run("status", "-c", "config/missing.yml", "--env-file", "missing.env")).isEqualTo(ErrorHandler.CONFIG_ERROR);
        assertThat(err.toString()).startsWith("설정 오류: ").contains("kdms.example.yml").doesNotContain("Exception");
    }

    @Test
    void 접속이_안되면_실패를_출력하고_종료코드_2(@TempDir Path dir) throws IOException {
        // 127.0.0.1:1 은 아무도 듣지 않는 포트 → 즉시 거부
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                KDMS_SRC_HOST=127.0.0.1
                KDMS_SRC_PORT=1
                KDMS_SRC_USER=u
                KDMS_SRC_PASSWORD=never-printed
                KDMS_TGT_HOST=127.0.0.1
                KDMS_TGT_PORT=1
                KDMS_TGT_PASSWORD=never-printed
                """);
        int code = run("status", "-c", "config/kdms.example.yml", "--env-file", env.toString());
        assertThat(code).isEqualTo(StatusCommand.CONNECTION_FAILED);
        assertThat(out.toString())
                .contains("[원천] u@127.0.0.1:1/KDMS_MOCK")
                .contains("[대상] kdms_app@127.0.0.1:1/kdms")
                .contains("실패")
                .contains("변환 규칙: 기본 + config/kdms-rules.yml, 타입 규칙")
                .doesNotContain("never-printed");
    }

    @Test
    void plan_은_원천에_못_붙으면_종료코드_2(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                KDMS_SRC_HOST=127.0.0.1
                KDMS_SRC_PORT=1
                KDMS_SRC_USER=u
                KDMS_SRC_PASSWORD=never-printed
                KDMS_TGT_HOST=127.0.0.1
                KDMS_TGT_PASSWORD=never-printed
                """);
        int code = run("plan", "-c", "config/kdms.example.yml", "--env-file", env.toString(), "-o", dir.resolve("out").toString());
        assertThat(code).isEqualTo(StatusCommand.CONNECTION_FAILED);
        assertThat(err.toString()).contains("원천 접속·조회 실패 (u@127.0.0.1:1/KDMS_MOCK)").doesNotContain("never-printed");
    }

    @Test
    void schema_의_단계_이름이_틀리면_오류() {
        assertThat(run("schema", "--phase", "later", "-c", "config/missing.yml")).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("pre-load | post-load | cutover");
    }

    @Test
    void 한글_표시폭_맞춤() {
        assertThat(StatusCommand.pad("버전")).isEqualTo("  버전" + " ".repeat(16));
        assertThat(StatusCommand.pad("SQL Agent")).isEqualTo("  SQL Agent" + " ".repeat(11));
    }
}
