package kdms.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.ValueRule;

class CopyValuesTest {

    private static final List<String> SENTINELS = List.of("1753-01-01", "1900-01-01", "9999-12-31");

    private static String copy(ColumnPlan c, Object value) throws SQLException {
        CopyValues.Column col = CopyValues.columns(List.of(c), "�", SENTINELS).get(0);
        StringBuilder b = new StringBuilder();
        boolean ok = CopyValues.append(b, MockPlans.one(value), 1, col);
        return ok ? b.toString() : "<NUL fail " + col.nulRows() + ">";
    }

    /** 같은 컬럼에 값 규칙만 바꾼 것 */
    private static ColumnPlan with(ColumnPlan c, ValueRule v) {
        return new ColumnPlan(c.source(), c.srcName(), c.srcType(), c.tgtName(), c.tgtType(), c.collate(), c.nullable(), c.identity(),
                c.defaultExpr(), c.generated(), c.check(), v, c.notes());
    }

    @Test
    void COPY_text_이스케이프_T_L15() {
        assertThat(CopyValues.escape("탭\t줄\r\n역\\슬래시")).isEqualTo("탭\\t줄\\r\\n역\\\\슬래시");
        assertThat(CopyValues.escape("\\N")).isEqualTo("\\\\N"); // 리터럴 \N 은 NULL 이 아니다
    }

    @Test
    void 타입별_값() throws SQLException {
        assertThat(copy(MockPlans.column("rating", "issue_amt"), new BigDecimal("922337203685477.5807"))).isEqualTo("922337203685477.5807");
        assertThat(copy(MockPlans.column("rating", "coupon_rate"), new BigDecimal("1E-4"))).isEqualTo("0.0001"); // 지수 표기 없음
        assertThat(copy(MockPlans.column("rating", "is_watch"), true)).isEqualTo("t");
        assertThat(copy(MockPlans.column("rating", "eff_dtm"), LocalDateTime.of(2026, 1, 2, 3, 4, 5, 997_000_000)))
                .isEqualTo("2026-01-02 03:04:05.997000"); // A05
        assertThat(copy(MockPlans.column("issuer", "issuer_guid"), "6F9619FF-8B86-D011-B42D-00C04FC964FF"))
                .isEqualTo("6f9619ff-8b86-d011-b42d-00c04fc964ff");
        assertThat(copy(MockPlans.column("issuer", "row_ver"), new byte[] {0, 0, 0, 0, 0, 0, 0x07, (byte) 0xD1}))
                .isEqualTo("\\\\x00000000000007d1"); // bytea 16진, COPY 안에서 역슬래시 두 번
        assertThat(copy(MockPlans.column("issuer", "issuer_nm_en"), null)).isEqualTo("\\N");
        assertThat(copy(MockPlans.column("rating_hist", "rating_id"), 42L)).isEqualTo("42");
    }

    @Test
    void datetime2_7자리는_마이크로초로_반올림_A06() {
        assertThat(CopyValues.round(LocalDateTime.of(2026, 9, 29, 0, 0, 0, 123_460_700), "half_up").getNano()).isEqualTo(123_461_000);
        assertThat(CopyValues.round(LocalDateTime.of(2026, 9, 29, 0, 0, 0, 123_460_400), "half_up").getNano()).isEqualTo(123_460_000);
        assertThat(CopyValues.round(LocalDateTime.of(2026, 9, 29, 0, 0, 0, 123_460_700), "truncate").getNano()).isEqualTo(123_460_000);
        // 올림이 날짜를 넘긴다
        assertThat(CopyValues.round(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_999_500), "half_up"))
                .isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        assertThat(CopyValues.time(LocalTime.of(23, 59, 59, 999_999_700), "half_up")).isEqualTo("24:00:00");
        assertThat(CopyValues.time(LocalTime.of(1, 2, 3, 400), "half_up")).isEqualTo("01:02:03.000000");
    }

    @Test
    void 문자_값_규칙_순서는_NUL_끝공백_대소문자() throws SQLException {
        ColumnPlan code = MockPlans.column("rating", "rating_cd");
        assertThat(copy(code, "AA+ ")).isEqualTo("AA+ "); // 기본 keep(B02)
        assertThat(copy(with(code, new ValueRule("rtrim", "upper", "replace", null, null)), "aa+\0 \t ")).isEqualTo("AA+� \\t");
        assertThat(copy(with(code, new ValueRule("rtrim", "keep", "strip", null, null)), "a\0  ")).isEqualTo("a");
        assertThat(CopyValues.rtrim("a \t ")).isEqualTo("a \t"); // RTRIM 은 공백만
    }

    @Test
    void NUL_fail_은_값을_쓰지_않고_센다_T_L09() throws SQLException {
        ColumnPlan nm = MockPlans.column("issuer", "issuer_nm_en"); // 기본 nul_char: fail
        assertThat(copy(nm, "x\0y")).isEqualTo("<NUL fail 1>");
        // 저장소 규칙: KIS 가 NUL 을 심은 issuer_nm 은 replace
        assertThat(MockPlans.column("issuer", "issuer_nm").value().nulChar()).isEqualTo("replace");
        assertThat(copy(MockPlans.column("issuer", "issuer_nm"), "NULL문자\0포함")).isEqualTo("NULL문자�포함");
    }

    @Test
    void 센티널_날짜_규칙_A08() throws SQLException {
        ColumnPlan pub = MockPlans.column("research_doc", "pub_dtm");
        LocalDateTime min = LocalDateTime.of(1753, 1, 1, 0, 0);
        LocalDateTime max = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 997_000_000);
        assertThat(copy(pub, min)).isEqualTo("1753-01-01 00:00:00.000000"); // 기본 keep
        assertThat(copy(with(pub, new ValueRule(null, null, null, "null", null)), min)).isEqualTo("\\N");
        assertThat(copy(with(pub, new ValueRule(null, null, null, "infinity", null)), min)).isEqualTo("-infinity");
        assertThat(copy(with(pub, new ValueRule(null, null, null, "infinity", null)), max)).isEqualTo("infinity");
        assertThat(copy(with(MockPlans.column("rating", "rating_dt"), new ValueRule(null, null, null, "null", null)), LocalDate.of(1900, 1, 1)))
                .isEqualTo("\\N");
    }

    @Test
    void 계산_컬럼은_적재하지_않는다() {
        SchemaPlan.TablePlan rating = MockPlans.table("rating");
        assertThat(rating.loadColumns()).extracting(ColumnPlan::srcName).doesNotContain("rating_rank").contains("rating_cd");
        assertThat(rating.loadColumns()).allSatisfy(c -> assertThat(CopyValues.supported(c.source().typeName())).isTrue());
    }
}
