package kdms.cdc;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import io.debezium.spi.storage.OffsetStorageReader;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore;

/**
 * Debezium 오프셋을 대상 PG 에 저장한다(JdbcOffsetBackingStore 그대로). 읽기만 고친다.
 * <p>
 * debezium-storage-jdbc 3.7.0 은 저장한 JSON 을 Jackson 으로 Map 으로 읽어 작은 정수를 Integer 로 돌려준다.
 * SQL Server 커넥터는 event_serial_no 를 Long 으로 꺼내므로 다시 시작할 때 ClassCastException 으로 멈춘다(2026-10-08 클라우드 실측).
 * 그래서 읽은 값의 Integer 를 Long 으로 바꾼다. SQL Server 오프셋의 정수(command_id·event_serial_no)는 Number·Long 으로 읽히므로 안전하다.
 * Debezium 을 고치지 않고(포크 금지) 공개 확장점(offset.storage 클래스)만 쓴다. docs/cdc.md §6.
 */
public class KdmsOffsetStore extends JdbcOffsetBackingStore {

    @Override
    public OffsetStorageReader createReader(String namespace) {
        OffsetStorageReader inner = super.createReader(namespace);
        return new OffsetStorageReader() {
            @Override
            public <T> Map<String, Object> offset(Map<String, T> partition) {
                return longs(inner.offset(partition));
            }

            @Override
            public <T> Map<Map<String, T>, Map<String, Object>> offsets(Collection<Map<String, T>> partitions) {
                Map<Map<String, T>, Map<String, Object>> in = inner.offsets(partitions);
                if (in == null) {
                    return null;
                }
                Map<Map<String, T>, Map<String, Object>> out = new LinkedHashMap<>();
                in.forEach((k, v) -> out.put(k, longs(v)));
                return out;
            }
        };
    }

    static Map<String, Object> longs(Map<String, Object> offset) {
        if (offset == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        offset.forEach((k, v) -> out.put(k, v instanceof Integer i ? Long.valueOf(i) : v));
        return out;
    }
}
