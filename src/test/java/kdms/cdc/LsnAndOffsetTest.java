package kdms.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LsnAndOffsetTest {

    @Test
    void LSN_표기와_순서() {
        assertThat(Lsn.format(new byte[] {0, 0, 0, 0x2d, 0, 0, 0x4f, (byte) 0xf0, 0, 3})).isEqualTo("0000002d:00004ff0:0003");
        assertThat(Lsn.normalize("0000002D:00004FF0:0003")).isEqualTo("0000002d:00004ff0:0003");
        assertThat(Lsn.normalize("abc")).isNull();
        assertThat(Lsn.max("0000002d:00004ff0:0003", "00000035:00002a98:0004")).isEqualTo("00000035:00002a98:0004");
        assertThat(Lsn.max(null, "00000035:00002a98:0004")).isEqualTo("00000035:00002a98:0004");
        Lsn.Position a = new Lsn.Position("0000002d:00006a38:0018", "0000002d:00006a38:0017", 1);
        Lsn.Position b = new Lsn.Position("0000002d:00006a38:0018", "0000002d:00006a38:0017", 2); // PK 변경: d(1) 다음 c(2)
        assertThat(a).isLessThan(b);
    }

    @Test
    void 저장된_오프셋의_Integer_는_Long_으로_읽는다() {
        Map<String, Object> in = new HashMap<>();
        in.put("event_serial_no", 1);
        in.put("command_id", 2);
        in.put("commit_lsn", "0000002d:00006a38:0018");
        Map<String, Object> out = KdmsOffsetStore.longs(in);
        assertThat(out.get("event_serial_no")).isEqualTo(1L);
        assertThat(out.get("command_id")).isInstanceOf(Number.class);
        assertThat(out.get("commit_lsn")).isEqualTo("0000002d:00006a38:0018");
        assertThat(KdmsOffsetStore.longs(null)).isNull();
    }

    @Test
    void 보존_기간_초과는_reset_안내로_T_C10() {
        String m = SyncRunner.explain("failed", new RuntimeException("The connector is trying to read change stream starting at "
                + "SqlServerOffsetContext [...] but this is no longer available on the server."));
        assertThat(m).contains("T-C10").contains("kdms reset --yes");
        assertThat(SyncRunner.explain(null, new RuntimeException("converting the nvarchar value '홍길동' failed"))).doesNotContain("홍길동");
    }
}
