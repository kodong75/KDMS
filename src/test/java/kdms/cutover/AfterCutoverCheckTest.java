package kdms.cutover;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import kdms.ddl.SchemaPlan.IndexPlan;

/** 전환 뒤 점검(kdms check): 다음 값 판정·설명, lower() 컬럼 읽기. DB 없음 */
class AfterCutoverCheckTest {

    private static BigInteger b(long v) {
        return BigInteger.valueOf(v);
    }

    @Test
    void 다음_값이_기대값이고_MAX_보다_크면_통과() {
        SequenceSync.Check c = new SequenceSync.Check("IDENTITY", "\"dbo\".\"t\".\"id\"", b(120), b(121), b(121), b(1), b(100));
        assertThat(c.ok()).isTrue();
        assertThat(AfterCutoverCheck.sequenceDetail(c)).isEqualTo("대상 다음 값 121 = 원천 120 + 1 · 대상 MAX 100");
    }

    @Test
    void setval_이_빠지면_실패하고_까닭을_적는다() {
        SequenceSync.Check c = new SequenceSync.Check("IDENTITY", "t", b(120), b(121), b(1), b(1), b(100));
        assertThat(c.ok()).isFalse();
        assertThat(AfterCutoverCheck.sequenceDetail(c)).contains("≠ 기대 121").contains("setval 이 빠졌다");
    }

    @Test
    void 기대값과_같아도_기존_키와_겹치면_실패() {
        // 원천 IDENT_CURRENT 가 대상 MAX 보다 작은데 기대값을 그대로 넣은 경우(choose 가 막지만 점검은 따로 본다)
        SequenceSync.Check c = new SequenceSync.Check("IDENTITY", "t", b(50), b(51), b(51), b(1), b(70));
        assertThat(c.ok()).isFalse();
        assertThat(AfterCutoverCheck.sequenceDetail(c)).contains("기존 키와 겹친다");
        SequenceSync.Check down = new SequenceSync.Check("IDENTITY", "t", b(-50), b(-51), b(-51), b(-1), b(-40));
        assertThat(down.ok()).isTrue();
    }

    @Test
    void 한_번도_안_쓴_SEQUENCE_는_원천_사용_안_함() {
        SequenceSync.Check c = new SequenceSync.Check("SEQUENCE", "\"dbo\".\"s\"", null, b(1000), b(1000), b(1), null);
        assertThat(c.ok()).isTrue();
        assertThat(AfterCutoverCheck.sequenceDetail(c)).isEqualTo("대상 다음 값 1000 = 원천 사용 안 함");
    }

    @Test
    void 유일_인덱스의_lower_컬럼만_읽는다() {
        IndexPlan ix = new IndexPlan("PK_x", "pk_daily_count_ci",
                "CREATE UNIQUE INDEX \"pk_daily_count_ci\" ON \"dbo\".\"daily_count\" (\"snap_dt\", lower(\"table_nm\"), lower(\"a\"\"b\"))", null);
        assertThat(AfterCutoverCheck.lowerColumns(ix)).containsExactly("table_nm", "a\"b");
        IndexPlan plain = new IndexPlan("ix", "ix_rating_dt", "CREATE INDEX \"ix_rating_dt\" ON \"dbo\".\"rating\" (\"rating_dt\")", null);
        assertThat(AfterCutoverCheck.lowerColumns(plain)).isEmpty();
    }
}
