package kdms.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import kdms.load.CopyValues;
import kdms.load.MockPlans;

/** Debezium 값 → payload → 정규 값 → COPY 와 같은 문자열(T-C05: 적재와 CDC 가 같은 값을 만든다) */
class CdcValuesTest {

    private static Object roundTrip(String type, Object debezium, String schema) {
        JsonNode n = CdcValues.encode(type, debezium, schema);
        return CdcValues.decode(type, n);
    }

    @Test
    void 날짜_시각_adaptive() {
        LocalDateTime dt = LocalDateTime.of(2026, 5, 2, 0, 2, 29, 997_000_000);
        long ms = dt.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertThat(roundTrip("datetime", ms, "io.debezium.time.Timestamp")).isEqualTo(dt); // .997 그대로(A05)
        LocalDateTime d7 = LocalDateTime.of(2026, 10, 8, 1, 2, 3, 123_456_700);
        long ns = d7.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + d7.getNano();
        assertThat(roundTrip("datetime2", ns, "io.debezium.time.NanoTimestamp")).isEqualTo(d7);
        LocalDateTime old = LocalDateTime.of(1753, 1, 1, 0, 0);
        assertThat(roundTrip("datetime", old.toInstant(ZoneOffset.UTC).toEpochMilli(), "io.debezium.time.Timestamp")).isEqualTo(old);
        assertThat(roundTrip("date", 20_000, "io.debezium.time.Date")).isEqualTo(LocalDate.ofEpochDay(20_000));
    }

    @Test
    void 숫자_문자_이진() {
        assertThat(roundTrip("money", new BigDecimal("922337203685477.5807"), null)).isEqualTo(new BigDecimal("922337203685477.5807"));
        assertThat(roundTrip("tinyint", (short) 255, null)).isEqualTo(255L);
        assertThat(roundTrip("bit", true, null)).isEqualTo(true);
        assertThat(roundTrip("uniqueidentifier", "6F9619FF-8B86-D011-B42D-00C04FC964FF", null)).isEqualTo("6F9619FF-8B86-D011-B42D-00C04FC964FF");
        assertThat((byte[]) roundTrip("timestamp", ByteBuffer.wrap(new byte[] {0, 0, 0, 0, 0, 0, 7, (byte) 0xD1}), null))
                .containsExactly(0, 0, 0, 0, 0, 0, 7, 0xD1);
        assertThat(roundTrip("nvarchar", "한글\0NUL", null)).isEqualTo("한글\0NUL"); // jsonb 는 U+0000 을 못 담아 b64
        assertThat(CdcValues.encode("nvarchar", "a\0b", null).has("b64")).isTrue();
        assertThat(roundTrip("varchar", null, null)).isNull();
    }

    @Test
    void 바뀌지_않은_LOB_은_값_없음_R5() {
        JsonNode n = CdcValues.encode("nvarchar", CdcValues.UNAVAILABLE, null);
        assertThat(CdcValues.unavailable(n)).isTrue();
        assertThatThrownBy(() -> CdcValues.decode("nvarchar", n)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 적재와_같은_변환기를_지난다_T_C05() {
        CopyValues.Column eff = CopyValues.columns(List.of(MockPlans.column("rating", "eff_dtm")), "�",
                List.of("1753-01-01", "1900-01-01", "9999-12-31")).get(0);
        LocalDateTime dt = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 997_000_000);
        Object v = roundTrip("datetime", dt.toInstant(ZoneOffset.UTC).toEpochMilli(), "io.debezium.time.Timestamp");
        assertThat(CopyValues.value(eff, v)).isEqualTo("2026-01-02 03:04:05.997000");
        CopyValues.Column guid = CopyValues.columns(List.of(MockPlans.column("issuer", "issuer_guid")), "�", List.of()).get(0);
        assertThat(CopyValues.value(guid, roundTrip("uniqueidentifier", "6F9619FF-8B86-D011-B42D-00C04FC964FF", null)))
                .isEqualTo("6f9619ff-8b86-d011-b42d-00c04fc964ff");
    }
}
