package kdms.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.Chunks.Chunk;

class ChunksTest {

    private static List<String> big(List<BigInteger> l) {
        return l.stream().map(BigInteger::toString).toList();
    }

    @Test
    void 정수_PK_균등_분할() {
        assertThat(big(Chunks.evenSplit(BigInteger.ONE, BigInteger.valueOf(100), 4))).containsExactly("26", "51", "76");
        assertThat(big(Chunks.evenSplit(BigInteger.ONE, BigInteger.valueOf(2), 4))).containsExactly("2"); // 값이 적으면 구간도 적다
        assertThat(Chunks.evenSplit(BigInteger.TEN, BigInteger.TEN, 4)).isEmpty();
        assertThat(big(Chunks.evenSplit(BigInteger.valueOf(-5), BigInteger.valueOf(4), 2))).containsExactly("0");
    }

    @Test
    void 경계값으로_빈틈없이_이어지는_구간() {
        List<Chunk> c = Chunks.fromStarts(List.of(List.of("26"), List.of("51")));
        assertThat(c).containsExactly(new Chunk(1, null, List.of("26")), new Chunk(2, List.of("26"), List.of("51")), new Chunk(3, List.of("51"), null));
        assertThat(Chunks.fromStarts(List.of())).containsExactly(new Chunk(1, null, null));
    }

    @Test
    void 복합_PK_구간_조건은_행값_비교를_펼친다() {
        TablePlan t = MockPlans.table("daily_count"); // PK (snap_dt date, table_nm sysname)
        List<ColumnPlan> keys = Chunks.keyColumns(t);
        assertThat(keys).extracting(ColumnPlan::srcName).containsExactly("snap_dt", "table_nm");
        List<String> params = new ArrayList<>();
        String w = Chunks.where(keys, new Chunk(2, List.of("2026-01-01", "a"), List.of("2026-02-01", "b")), params);
        assertThat(w).isEqualTo("\nWHERE ([snap_dt] > CONVERT(date, ?, 23) OR ([snap_dt] = CONVERT(date, ?, 23) AND [table_nm] >= ?))"
                + "\n  AND ([snap_dt] < CONVERT(date, ?, 23) OR ([snap_dt] = CONVERT(date, ?, 23) AND [table_nm] < ?))");
        assertThat(params).containsExactly("2026-01-01", "2026-01-01", "a", "2026-02-01", "2026-02-01", "b");
    }

    @Test
    void 구간_SELECT_는_계산_컬럼을_빼고_원천_이름을_쓴다() {
        TablePlan t = MockPlans.table("rating");
        List<String> params = new ArrayList<>();
        String sql = Chunks.selectSql(t, t.loadColumns(), new Chunk(1, null, List.of("10003")), params);
        assertThat(sql).startsWith("SELECT [rating_id], [issuer_id], [rating_cd]").doesNotContain("rating_rank")
                .endsWith("FROM [dbo].[rating]\nWHERE [rating_id] < CAST(? AS bigint)");
        assertThat(params).containsExactly("10003");
        assertThat(Chunks.selectSql(t, t.loadColumns(), new Chunk(1, null, null), new ArrayList<>())).doesNotContain("WHERE");
    }

    @Test
    void NTILE_경계값_SQL() {
        TablePlan t = MockPlans.table("code_master");
        String sql = Chunks.ntileSql(t, Chunks.keyColumns(t), 3);
        assertThat(sql).contains("NTILE(3) OVER (ORDER BY [code_grp], [code])").contains("WHERE rn = 1 AND nt > 1");
    }
}
