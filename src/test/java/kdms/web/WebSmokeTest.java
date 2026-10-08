package kdms.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import kdms.cli.WebCommand;
import kdms.config.KdmsConfig;
import kdms.rules.RulesLoader;

/** DB 없이 웹 화면이 뜨고, 접속 실패를 화면에 보여 주는지. */
class WebSmokeTest {

    private static ConfigurableApplicationContext ctx;
    private static String base;
    private static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeAll
    static void start() {
        KdmsConfig.Endpoint nowhere = new KdmsConfig.Endpoint("127.0.0.1", 1, "db", "u", "never-shown", Map.of());
        KdmsConfig cfg = new KdmsConfig("smoke", nowhere, nowhere, new KdmsConfig.LoadSettings(1, 1, "snapshot"), new KdmsConfig.SyncSettings(1000, 500, 10),
                new KdmsConfig.TableSelection(List.of("dbo.*"), List.of()), "", new KdmsConfig.WebSettings("127.0.0.1", 0));
        ctx = WebCommand.start(cfg, RulesLoader.load(null));
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 첫화면() throws Exception {
        HttpResponse<String> r = get("/");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("KDMS").contains("원천").contains("대상").contains("접속 실패")
                .contains("/kdms.css").doesNotContain("never-shown");
    }

    @Test
    void 정적파일() throws Exception {
        assertThat(get("/kdms.css").statusCode()).isEqualTo(200);
        assertThat(get("/kdms.js").statusCode()).isEqualTo(200);
    }

    @Test
    void 상태_API() throws Exception {
        HttpResponse<String> r = get("/api/status");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("json"));
        assertThat(r.body()).contains("\"connected\":false").contains("\"jobName\":\"smoke\"").doesNotContain("never-shown");
    }
}
