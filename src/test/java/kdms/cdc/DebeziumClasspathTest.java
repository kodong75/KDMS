package kdms.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;

/**
 * pom 에서 Jetty·Jersey 등을 뺀 뒤에도 Debezium Embedded 엔진 + SQL Server 커넥터가 클래스 누락 없이 뜨는지(plan.md R3).
 * DB 가 없으므로 접속 단계에서 실패해야 정상이고, 실패 원인이 클래스 누락이면 안 된다.
 */
class DebeziumClasspathTest {

    @Test
    void 엔진이_접속_단계까지_간다() throws Exception {
        Properties p = new Properties();
        p.setProperty("name", "kdms-classpath-smoke");
        p.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
        p.setProperty("offset.storage", "org.apache.kafka.connect.storage.MemoryOffsetBackingStore");
        p.setProperty("schema.history.internal", "io.debezium.relational.history.MemorySchemaHistory");
        p.setProperty("topic.prefix", "kdms");
        p.setProperty("database.hostname", "127.0.0.1");
        p.setProperty("database.port", "1");
        p.setProperty("database.user", "u");
        p.setProperty("database.password", "p");
        p.setProperty("database.names", "KDMS_MOCK");
        p.setProperty("database.encrypt", "false");
        p.setProperty("snapshot.mode", "no_data");
        p.setProperty("errors.max.retries", "0");

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(Json.class)
                .using(p)
                .notifying(r -> { })
                .using((success, msg, err) -> {
                    message.set(msg);
                    error.set(err);
                    done.countDown();
                })
                .build();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.execute(engine);
            assertThat(done.await(120, TimeUnit.SECONDS)).as("엔진이 끝나지 않음").isTrue();
        } finally {
            try {
                engine.close();
            } catch (IllegalStateException alreadyStopped) {
                // 접속 실패로 엔진이 스스로 멈춘 경우
            }
            ex.shutdownNow();
        }
        String all = message.get() + " / " + chain(error.get());
        assertThat(all).doesNotContain("NoClassDefFoundError").doesNotContain("ClassNotFoundException");
        // 접속 거부까지 갔다는 증거(커넥터 검증 또는 시작 단계의 접속 오류)
        assertThat(all.toLowerCase()).containsAnyOf("connect", "tcp/ip", "refused", "unable");
    }

    /** 접속 뒤(스트리밍 단계)에야 쓰이는 클래스도 연결(link)까지 되는지. 4단계 실측 전의 사전 확인. */
    @Test
    void 스트리밍_단계_클래스가_모두_로드된다() throws Exception {
        for (String name : new String[] {
                "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore",
                "io.debezium.storage.jdbc.history.JdbcSchemaHistory",
                "io.debezium.connector.sqlserver.SqlServerConnectorTask",
                "io.debezium.connector.sqlserver.SqlServerStreamingChangeEventSource",
                "io.debezium.connector.sqlserver.SqlServerSnapshotChangeEventSource",
                "io.debezium.connector.sqlserver.SqlServerConnection",
                "io.debezium.connector.sqlserver.SqlServerOffsetContext",
                "io.debezium.embedded.async.AsyncEmbeddedEngine",
                "io.debezium.embedded.async.ParallelSmtBatchProcessor",
                "org.apache.kafka.connect.runtime.WorkerConfig",
                "org.apache.kafka.connect.storage.OffsetStorageWriter",
                "org.apache.kafka.connect.json.JsonConverter"}) {
            Class<?> c = Class.forName(name, true, getClass().getClassLoader());
            // 메서드·필드 시그니처의 타입까지 해석해 빠진 의존성을 드러낸다
            c.getDeclaredMethods();
            c.getDeclaredFields();
            c.getDeclaredConstructors();
        }
    }

    private static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        return b.toString();
    }
}
