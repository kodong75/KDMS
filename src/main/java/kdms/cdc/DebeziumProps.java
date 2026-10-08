package kdms.cdc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import kdms.config.Jdbc;
import kdms.config.KdmsConfig;

/**
 * Debezium Embedded 엔진 설정(plan.md §4.4, docs/cdc.md §2). Kafka 브로커 없이 엔진 하나가 원천 CDC 변경 테이블을 읽는다.
 * 오프셋·스키마 이력은 debezium-storage-jdbc 로 대상 PG 의 kdms 스키마에 작업별 표로 둔다("상태는 대상 PG 한 곳").
 * <p>
 * 이 Properties 에는 두 DB 비밀번호가 들어 있다. 로그·화면에 그대로 찍지 않는다({@link #masked}).
 */
public final class DebeziumProps {

    /** 원천 주제 접두어. 브로커가 없으니 이름표로만 쓴다(하트비트 레코드 주제 = __debezium-heartbeat.kdms) */
    public static final String TOPIC_PREFIX = "kdms";

    private DebeziumProps() {
    }

    /** 엔진 이름 = 오프셋 키. 작업 번호를 넣어 같은 이름의 작업을 다시 만들어도 옛 오프셋을 쓰지 않게 한다 */
    public static String engineName(long jobId) {
        return "kdms-job-" + jobId;
    }

    /** kdms 스키마 안 오프셋 표(작업별). JdbcOffsetBackingStore 는 저장할 때 표 전체를 지우고 다시 쓰므로 작업끼리 나눈다 */
    public static String offsetTable(long jobId) {
        return "debezium_offset_" + jobId;
    }

    /** kdms 스키마 안 스키마 이력 표(작업별). 이 표에는 엔진 이름 구분이 없다 */
    public static String historyTable(long jobId) {
        return "debezium_schema_history_" + jobId;
    }

    /**
     * @param tables 캡처할 원천 테이블("schema.table", 원천 이름 그대로)
     */
    public static Properties build(KdmsConfig cfg, long jobId, List<String> tables) {
        KdmsConfig.Endpoint s = cfg.source();
        Properties p = new Properties();
        p.setProperty("name", engineName(jobId));
        p.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
        p.setProperty("topic.prefix", TOPIC_PREFIX);

        p.setProperty("database.hostname", s.host());
        p.setProperty("database.port", String.valueOf(s.port()));
        p.setProperty("database.user", s.user());
        p.setProperty("database.password", s.password());
        p.setProperty("database.names", s.database());
        // 운영 기본값은 인증서 검증(Jdbc.openSource 와 같다). 설정 파일의 드라이버 속성을 그대로 넘긴다
        p.setProperty("database.encrypt", s.properties().getOrDefault("encrypt", "true"));
        p.setProperty("driver.applicationName", "kdms-cdc");
        for (Map.Entry<String, String> e : s.properties().entrySet()) {
            p.setProperty("driver." + e.getKey(), e.getValue());
        }
        p.setProperty("table.include.list", tables.stream().map(DebeziumProps::regex).collect(Collectors.joining(",")));

        // 데이터 스냅숏은 KDMS 적재기가 한다. 엔진은 스키마만 읽고 지금 위치부터 스트리밍(plan.md §3.1)
        p.setProperty("snapshot.mode", "no_data");
        // 값 표현: 전체 적재 경로와 같은 정규 값으로 모으기 위한 설정(CdcValues, R4)
        p.setProperty("decimal.handling.mode", "precise");
        p.setProperty("binary.handling.mode", "bytes");
        p.setProperty("time.precision.mode", "adaptive");
        // 삭제 뒤 빈 레코드(Kafka 압축용)는 필요 없다
        p.setProperty("tombstones.on.delete", "false");
        // 하트비트: 스트리밍이 시작됐다는 표시(워터마크 기록)이자 변경이 없을 때도 처리 위치를 알려 준다(sync --drain)
        p.setProperty("heartbeat.interval.ms", "1000");
        p.setProperty("poll.interval.ms", "500");

        String url = targetUrl(cfg.target());
        p.setProperty("offset.storage", KdmsOffsetStore.class.getName());
        p.setProperty("offset.storage.jdbc.connection.url", url);
        p.setProperty("offset.storage.jdbc.connection.user", cfg.target().user());
        p.setProperty("offset.storage.jdbc.connection.password", cfg.target().password());
        p.setProperty("offset.storage.jdbc.table.name", offsetTable(jobId));
        p.setProperty("offset.storage.jdbc.table.ddl", "CREATE TABLE IF NOT EXISTS %s (id varchar(36) NOT NULL, offset_key text, "
                + "offset_val text, record_insert_ts timestamp NOT NULL, record_insert_seq integer NOT NULL)");
        // 배치를 change_log 에 커밋한 뒤 바로 오프셋을 커밋한다(최소 1회 전달 + change_log UNIQUE = 결과 1회)
        p.setProperty("offset.flush.interval.ms", "0");

        p.setProperty("schema.history.internal", "io.debezium.storage.jdbc.history.JdbcSchemaHistory");
        p.setProperty("schema.history.internal.jdbc.connection.url", url);
        p.setProperty("schema.history.internal.jdbc.connection.user", cfg.target().user());
        p.setProperty("schema.history.internal.jdbc.connection.password", cfg.target().password());
        p.setProperty("schema.history.internal.jdbc.table.name", historyTable(jobId));
        p.setProperty("schema.history.internal.jdbc.table.ddl", "CREATE TABLE IF NOT EXISTS %s (id varchar(36) NOT NULL, "
                + "history_data text, history_data_seq integer, record_insert_ts timestamp NOT NULL, record_insert_seq integer NOT NULL, "
                + "PRIMARY KEY (id, history_data_seq))");
        p.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        return p;
    }

    /**
     * 대상 PG URL. currentSchema=kdms 라서 표 이름을 스키마 없이 준다(debezium-storage-jdbc 는 표가 있는지
     * DatabaseMetaData.getTables(이름) 으로 보므로 "kdms.표" 처럼 스키마를 붙이면 못 찾는다). 비밀번호는 넣지 않는다.
     */
    static String targetUrl(KdmsConfig.Endpoint t) {
        StringBuilder b = new StringBuilder(Jdbc.targetUrl(t)).append("?currentSchema=kdms&ApplicationName=kdms-cdc");
        for (Map.Entry<String, String> e : t.properties().entrySet()) {
            b.append('&').append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    /** "dbo.rating" → 정규식 dbo\.rating (Debezium 은 schema.table 전체와 맞춘다) */
    static String regex(String table) {
        return table.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    /** 확인용 출력: 비밀번호 값을 가린다 */
    public static String masked(Properties p) {
        return p.stringPropertyNames().stream().sorted()
                .map(k -> k + "=" + (k.toLowerCase(java.util.Locale.ROOT).contains("password") ? "****" : p.getProperty(k)))
                .collect(Collectors.joining("\n"));
    }
}
