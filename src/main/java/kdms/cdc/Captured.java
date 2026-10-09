package kdms.cdc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;

/**
 * Debezium 레코드 하나를 KDMS 가 쓰는 모양으로 가른다: 행 변경 / 하트비트 / 스키마 변경.
 * 행 값은 이 클래스 밖으로 change_log.payload JSON 으로만 나간다(로그·오류 메시지에 쓰지 않는다, R8).
 */
public final class Captured {

    /** 레코드 종류 */
    public enum Kind {
        /** 행 입력·수정·삭제 */
        CHANGE,
        /** 스트리밍 중 처리 위치 알림(변경 없음) */
        HEARTBEAT,
        /** 엔진 시작 때 스키마 읽기(스냅숏) 결과. 무시한다 */
        SCHEMA_SNAPSHOT,
        /** 스트리밍 중 스키마 변경(원천 DDL, R6) */
        SCHEMA_CHANGE
    }

    /**
     * @param streamLsn    이 레코드의 오프셋 commit_lsn(Debezium 이 처리한 위치). 없으면 null
     * @param position     행 변경의 위치(CHANGE 만)
     * @param op           c | u | d (CHANGE 만)
     * @param table        원천 "schema.table"(CHANGE 만, 원천 표기 그대로)
     * @param payload      {"before": {...}, "after": {...}} (CHANGE 만)
     * @param srcCommitAt  원천 커밋 시각(Debezium source.ts_ms)
     */
    public record Record(Kind kind, String streamLsn, Lsn.Position position, String op, String schema, String table,
                         ObjectNode payload, Instant srcCommitAt) {
    }

    /** 원천 "schema.table"(소문자) → 계획. 계획에 없는 테이블의 변경은 오류로 본다(엔진이 계획 테이블만 캡처한다) */
    private final Map<String, TablePlan> tables = new HashMap<>();

    public Captured(List<TablePlan> plans) {
        for (TablePlan t : plans) {
            tables.put(key(t.srcSchema(), t.srcName()), t);
        }
    }

    static String key(String schema, String table) {
        return (schema + "." + table).toLowerCase(Locale.ROOT);
    }

    public Record parse(SourceRecord r) {
        Map<String, ?> offset = r.sourceOffset();
        String streamLsn = offset == null ? null : Lsn.normalize(offset.get("commit_lsn"));
        String topic = r.topic() == null ? "" : r.topic();
        if (topic.startsWith("__debezium-heartbeat")) {
            return new Record(Kind.HEARTBEAT, streamLsn, null, null, null, null, null, null);
        }
        if (!(r.value() instanceof Struct v) || v.schema().field("op") == null) {
            boolean snapshot = offset != null && offset.get("snapshot") != null && !"false".equals(String.valueOf(offset.get("snapshot")));
            return new Record(snapshot ? Kind.SCHEMA_SNAPSHOT : Kind.SCHEMA_CHANGE, streamLsn, null, null, null, null, null, null);
        }
        String op = v.getString("op");
        Struct src = v.getStruct("source");
        String schema = src.getString("schema");
        String table = src.getString("table");
        if (!"c".equals(op) && !"u".equals(op) && !"d".equals(op)) {
            throw new IllegalStateException(schema + "." + table + ": 처리하지 않는 Debezium 연산 '" + op + "'");
        }
        TablePlan plan = tables.get(key(schema, table));
        if (plan == null) {
            throw new IllegalStateException("계획에 없는 테이블의 변경: " + schema + "." + table);
        }
        Lsn.Position pos = new Lsn.Position(Lsn.normalize(src.getString("commit_lsn")), Lsn.normalize(src.getString("change_lsn")),
                src.getInt64("event_serial_no"));
        if (pos.commitLsn() == null || pos.changeLsn() == null) {
            throw new IllegalStateException(schema + "." + table + ": 변경 위치(LSN)가 없는 이벤트");
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("before", row(plan, v.getStruct("before")));
        payload.set("after", row(plan, v.getStruct("after")));
        Long ts = src.getInt64("ts_ms");
        return new Record(Kind.CHANGE, streamLsn, pos, op, schema, table, payload, ts == null ? null : Instant.ofEpochMilli(ts));
    }

    /** 계획의 값 컬럼(계산 컬럼 제외)만 담는다. 계획 컬럼이 이벤트에 없으면 원천 DDL 이 바뀐 것(R6) */
    private static com.fasterxml.jackson.databind.JsonNode row(TablePlan plan, Struct s) {
        if (s == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        for (ColumnPlan c : plan.loadColumns()) {
            Field f = s.schema().field(c.srcName());
            if (f == null) {
                throw new IllegalStateException(plan.srcQualified() + "." + c.srcName()
                        + ": CDC 이벤트에 이 컬럼이 없다. 원천 스키마가 바뀌었으면 반영을 멈춘다(plan.md R6)");
            }
            o.set(c.srcName(), CdcValues.encode(c.source().typeName(), s.get(f), f.schema().name()));
        }
        return o;
    }
}
