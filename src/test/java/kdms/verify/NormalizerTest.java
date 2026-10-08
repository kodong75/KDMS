package kdms.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.ddl.SchemaPlan.ValueRule;
import kdms.load.MockPlans;

class NormalizerTest {

    private static final List<String> SENTINELS = List.of("1753-01-01", "1900-01-01", "9999-12-31");

    private static Normalizer.Expr expr(String table, String column) {
        return Normalizer.column(MockPlans.column(table, column), SENTINELS, "�");
    }

    private static ColumnPlan with(ColumnPlan c, ValueRule v) {
        return new ColumnPlan(c.source(), c.srcName(), c.srcType(), c.tgtName(), c.tgtType(), c.collate(), c.nullable(), c.identity(),
                c.defaultExpr(), c.generated(), c.check(), v, c.notes());
    }

    /** KIS:docs/normalization.md §1 표와 같은 식 */
    @Test
    void 타입별_정규화_식은_KIS_normalization_과_같다() {
        assertThat(expr("rating", "eff_dtm")).extracting(Normalizer.Expr::source, Normalizer.Expr::target)
                .containsExactly("CONVERT(char(23), [eff_dtm], 121)", "to_char(\"eff_dtm\", 'YYYY-MM-DD HH24:MI:SS.MS')");
        assertThat(expr("issuer", "upd_dtm").source()).isEqualTo("CONVERT(char(26), CAST([upd_dtm] AS datetime2(6)), 121)");
        assertThat(expr("rating", "issue_amt").source()).isEqualTo("CONVERT(varchar(50), CAST([issue_amt] AS decimal(19,4)))");
        assertThat(expr("rating", "is_watch")).extracting(Normalizer.Expr::source, Normalizer.Expr::target)
                .containsExactly("CASE [is_watch] WHEN 1 THEN '1' WHEN 0 THEN '0' END",
                        "CASE WHEN \"is_watch\" THEN '1' WHEN NOT \"is_watch\" THEN '0' END");
        assertThat(expr("issuer", "issuer_guid").source()).isEqualTo("LOWER(CONVERT(char(36), [issuer_guid]))");
        assertThat(expr("issuer", "row_ver").target()).isEqualTo("upper(encode(\"row_ver\", 'hex'))");
        assertThat(expr("issuer", "issuer_cd")).extracting(Normalizer.Expr::source, Normalizer.Expr::target)
                .containsExactly("RTRIM(CAST([issuer_cd] AS nvarchar(max)))", "rtrim(\"issuer_cd\"::text)"); // A10
        assertThat(expr("rating", "rating_cd").source()).isEqualTo("CAST([rating_cd] AS nvarchar(max))"); // 끝 공백 keep
        assertThat(expr("rating", "rating_dt").target()).isEqualTo("to_char(\"rating_dt\", 'YYYY-MM-DD')");
    }

    @Test
    void 생성한_검증_SQL_에_ISNULL_이_없다_T_L17() {
        for (TablePlan t : MockPlans.plan().tables()) {
            List<Normalizer.Expr> e = t.columns().stream().map(c -> Normalizer.column(c, SENTINELS, "�")).toList();
            List<ColumnPlan> sums = t.columns().stream().filter(Normalizer::summed).toList();
            String sql = Verifier.sourceSummarySql(t, e, sums) + Verifier.targetSummarySql(t, e, sums);
            assertThat(sql).doesNotContainIgnoringCase("ISNULL").contains("COALESCE(").contains("N'\\N'");
        }
    }

    @Test
    void 값_규칙을_원천_쪽에_같은_순서로_적용한다() {
        // NUL → 끝 공백 → 대소문자. NUL 치환 콜레이션은 해시의 UTF-8 콜레이션과 같아야 한글이 깨지지 않는다
        ColumnPlan cd = with(MockPlans.column("rating", "rating_cd"), new ValueRule("rtrim", "upper", "replace", null, null));
        assertThat(Normalizer.column(cd, SENTINELS, "�").source())
                .isEqualTo("UPPER(RTRIM(REPLACE(CAST([rating_cd] AS nvarchar(max)) COLLATE Latin1_General_100_BIN2_UTF8, NCHAR(0), N'�')))");
        assertThat(expr("issuer", "issuer_nm").source()) // 저장소 규칙 nul_char: replace
                .isEqualTo("REPLACE(CAST([issuer_nm] AS nvarchar(max)) COLLATE Latin1_General_100_BIN2_UTF8, NCHAR(0), N'�')");
        assertThat(expr("issuer", "issuer_nm").target()).isEqualTo("\"issuer_nm\"::text"); // 대상엔 이미 바뀐 값
    }

    @Test
    void 센티널과_반올림_규칙() {
        ColumnPlan pub = MockPlans.column("research_doc", "pub_dtm");
        assertThat(Normalizer.column(with(pub, new ValueRule(null, null, null, "null", null)), SENTINELS, "").source())
                .isEqualTo("CASE WHEN CAST([pub_dtm] AS date) IN ('1753-01-01', '1900-01-01', '9999-12-31') THEN NULL ELSE CONVERT(char(23), [pub_dtm], 121) END");
        Normalizer.Expr inf = Normalizer.column(with(pub, new ValueRule(null, null, null, "infinity", null)), SENTINELS, "");
        assertThat(inf.source()).contains("IN ('1753-01-01', '1900-01-01') THEN '-infinity'").contains("IN ('9999-12-31') THEN 'infinity'");
        assertThat(inf.target()).startsWith("CASE WHEN \"pub_dtm\" = 'infinity' THEN 'infinity'");
        assertThat(Normalizer.micro("[x]", "truncate")).isEqualTo("DATEADD(NANOSECOND, -(DATEPART(NANOSECOND, [x]) % 1000), [x])");
    }

    @Test
    void 합계는_정수_decimal_money_를_decimal_38_로_A04() {
        TablePlan rating = MockPlans.table("rating");
        assertThat(rating.columns().stream().filter(Normalizer::summed).map(ColumnPlan::srcName))
                .containsExactly("rating_id", "issuer_id", "issue_amt", "coupon_rate", "rating_rank");
        assertThat(Normalizer.sourceSum(MockPlans.column("rating", "issue_amt"))).isEqualTo("SUM(CAST([issue_amt] AS decimal(38,4)))");
        assertThat(Normalizer.sourceSum(MockPlans.column("rating", "coupon_rate"))).isEqualTo("SUM(CAST([coupon_rate] AS decimal(38,4)))");
        assertThat(Normalizer.targetSum(MockPlans.column("rating", "rating_id"))).isEqualTo("sum(\"rating_id\")::numeric");
    }

    @Test
    void 검사_비교는_스케일과_관계없다() {
        assertThat(new Verifier.Check("sum", "x", new java.math.BigDecimal("1.50"), new java.math.BigDecimal("1.5000")).matched()).isTrue();
        assertThat(new Verifier.Check("hash", "", null, null).matched()).isTrue(); // 빈 테이블
        assertThat(new Verifier.Check("hash", "", null, java.math.BigDecimal.ONE).matched()).isFalse();
        SchemaPlan p = MockPlans.plan();
        assertThat(p.tables()).hasSize(7);
    }
}
