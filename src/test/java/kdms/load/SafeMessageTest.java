package kdms.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** plan.md R8: 오류 메시지에 행 값이 남지 않는다 */
class SafeMessageTest {

    @Test
    void PG_COPY_오류의_값과_Detail_을_지우고_위치는_남긴다() {
        String pg = "ERROR: invalid input syntax for type integer: \"김철수 010-1234\"\n"
                + "  Where: COPY issuer, line 17, column issuer_id: \"김철수 010-1234\"";
        assertThat(SafeMessage.of(pg)).isEqualTo("ERROR: invalid input syntax for type integer: \"…\" (COPY issuer 17번째 행, 컬럼 issuer_id)")
                .doesNotContain("김철수");
        String notNull = "ERROR: null value in column \"issuer_nm\" of relation \"issuer\" violates not-null constraint\n"
                + "  Detail: Failing row contains (1, 비밀값, null).\n  Where: COPY issuer, line 3: \"1\t비밀값\t\\N\"";
        assertThat(SafeMessage.of(notNull)).doesNotContain("비밀값").contains("column \"issuer_nm\"").contains("COPY issuer 3번째 행");
    }

    @Test
    void MS_SQL_변환_오류의_값을_지운다() {
        assertThat(SafeMessage.of("Conversion failed when converting the nvarchar value '홍길동' to data type int."))
                .isEqualTo("Conversion failed when converting the nvarchar value '…' to data type int.");
    }
}
