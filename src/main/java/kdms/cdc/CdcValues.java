package kdms.cdc;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Debezium(SQL Server 커넥터) 값 ↔ change_log.payload JSON ↔ 정규 값.
 * <p>
 * 전체 적재는 JDBC 로 읽은 값을, CDC 반영은 Debezium 값을 같은 정규 값(문자열·Long·BigDecimal·LocalDateTime…,
 * {@code kdms.load.CopyValues#fetch} 참고)으로 모은 뒤 같은 변환기({@code CopyValues.value})를 지나게 한다(plan.md §5 끝, R4).
 * 값 규칙(NUL·끝 공백·대소문자·센티널·반올림)은 반영할 때 적용한다. 그래서 payload 에는 원천 값 그대로를 담는다.
 * <p>
 * Debezium 설정(DebeziumProps): decimal.handling.mode=precise, binary.handling.mode=bytes, time.precision.mode=adaptive.
 * adaptive 의 날짜·시각은 원천 벽시계 값을 UTC 로 본 epoch 값이라 UTC 로 되돌리면 원천 값 그대로다.
 * <p>
 * JSON 표기: 값은 문자열(정밀도·자릿수를 잃지 않게), NULL 은 null. PG jsonb 가 U+0000 을 못 담으므로 NUL 이 든 문자열은
 * {"b64": UTF-8 의 Base64}. LOB 컬럼이 UPDATE 에서 바뀌지 않아 Debezium 이 값을 주지 않은 경우는 {"unavailable": true}(R5).
 */
public final class CdcValues {

    /** Debezium 이 값 대신 넣는 표시(unavailable.value.placeholder 기본값) */
    public static final String UNAVAILABLE = "__debezium_unavailable_value";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private CdcValues() {
    }

    /**
     * Debezium 값 → payload JSON 값.
     *
     * @param type       원천 타입 이름(sys.types, 예: datetime2)
     * @param schemaName Debezium 필드 스키마 이름(예: io.debezium.time.NanoTimestamp), 없으면 null
     */
    public static JsonNode encode(String type, Object v, String schemaName) {
        if (v == null) {
            return JSON.nullNode();
        }
        if (UNAVAILABLE.equals(v)) {
            return JSON.objectNode().put("unavailable", true);
        }
        return switch (type) {
            case "char", "varchar", "nchar", "nvarchar", "sysname", "text", "ntext", "uniqueidentifier", "xml" -> {
                String s = v.toString();
                yield s.indexOf('\0') >= 0
                        ? JSON.objectNode().put("b64", Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)))
                        : JSON.textNode(s);
            }
            case "bigint", "int", "smallint", "tinyint" -> JSON.textNode(Long.toString(((Number) v).longValue()));
            case "bit" -> JSON.textNode(Boolean.toString((Boolean) v));
            case "decimal", "numeric", "money", "smallmoney" -> JSON.textNode(((BigDecimal) v).toPlainString());
            case "float" -> JSON.textNode(Double.toString(((Number) v).doubleValue()));
            case "real" -> JSON.textNode(Float.toString(((Number) v).floatValue()));
            case "date" -> JSON.textNode(LocalDate.ofEpochDay(((Number) v).longValue()).toString());
            case "datetime", "smalldatetime", "datetime2" -> JSON.textNode(timestamp(((Number) v).longValue(), schemaName).toString());
            case "datetimeoffset" -> JSON.textNode(OffsetDateTime.parse(v.toString()).toString());
            case "time" -> JSON.textNode(time(((Number) v).longValue(), schemaName).toString());
            case "binary", "varbinary", "image", "rowversion", "timestamp" -> JSON.textNode(HexFormat.of().formatHex(bytes(v)));
            default -> throw new IllegalStateException("CDC 로 받을 수 없는 원천 타입: " + type);
        };
    }

    /** payload 값이 "바뀌지 않아 값 없음"(LOB) 인가 */
    public static boolean unavailable(JsonNode n) {
        return n != null && n.isObject() && n.path("unavailable").asBoolean(false);
    }

    /** payload JSON 값 → 정규 값(CopyValues.value 의 입력). JSON null 이면 null */
    public static Object decode(String type, JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (unavailable(n)) {
            throw new IllegalStateException("값이 없는 LOB 컬럼(R5)은 정규 값으로 바꿀 수 없다");
        }
        String s = n.isObject() ? new String(Base64.getDecoder().decode(n.path("b64").asText()), StandardCharsets.UTF_8) : n.asText();
        return switch (type) {
            case "char", "varchar", "nchar", "nvarchar", "sysname", "text", "ntext", "uniqueidentifier", "xml" -> s;
            case "bigint", "int", "smallint", "tinyint" -> Long.parseLong(s);
            case "bit" -> Boolean.parseBoolean(s);
            case "decimal", "numeric", "money", "smallmoney" -> new BigDecimal(s);
            case "float" -> Double.parseDouble(s);
            case "real" -> Float.parseFloat(s);
            case "date" -> LocalDate.parse(s);
            case "datetime", "smalldatetime", "datetime2" -> LocalDateTime.parse(s);
            case "datetimeoffset" -> OffsetDateTime.parse(s);
            case "time" -> LocalTime.parse(s);
            case "binary", "varbinary", "image", "rowversion", "timestamp" -> HexFormat.of().parseHex(s);
            default -> throw new IllegalStateException("CDC 로 받을 수 없는 원천 타입: " + type);
        };
    }

    /** adaptive: Timestamp = 밀리초, MicroTimestamp = 마이크로초, NanoTimestamp = 나노초(datetime2(7)) */
    static LocalDateTime timestamp(long v, String schemaName) {
        Instant i;
        if ("io.debezium.time.NanoTimestamp".equals(schemaName)) {
            i = Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000_000L), Math.floorMod(v, 1_000_000_000L));
        } else if ("io.debezium.time.MicroTimestamp".equals(schemaName)) {
            i = Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000L), Math.floorMod(v, 1_000_000L) * 1000L);
        } else {
            i = Instant.ofEpochMilli(v);
        }
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    /** adaptive: Time = 밀리초, MicroTime = 마이크로초, NanoTime = 나노초(하루 안의 위치) */
    static LocalTime time(long v, String schemaName) {
        long nanos;
        if ("io.debezium.time.NanoTime".equals(schemaName)) {
            nanos = v;
        } else if ("io.debezium.time.MicroTime".equals(schemaName)) {
            nanos = v * 1000L;
        } else {
            nanos = v * 1_000_000L;
        }
        return LocalTime.ofNanoOfDay(nanos);
    }

    private static byte[] bytes(Object v) {
        if (v instanceof byte[] b) {
            return b;
        }
        ByteBuffer bb = ((ByteBuffer) v).duplicate();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        return b;
    }
}
