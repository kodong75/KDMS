package kdms.cutover;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 전환: setval 값 고르기(A09, T-L05·T-L06)와 결과 표. DB 없음 */
class CutoverTest {

    private static BigInteger b(long v) {
        return BigInteger.valueOf(v);
    }

    @Test
    void IDENTITY_는_MAX_가_아니라_IDENT_CURRENT_다음부터() {
        // 원천에서 끝 행을 지워 IDENT_CURRENT(120) > 대상 MAX(100)
        SequenceSync.Value v = SequenceSync.choose(b(120), b(1), b(1), b(100));
        assertThat(v.called()).isTrue();
        assertThat(v.next(b(1))).isEqualTo(b(121));
        assertThat(v.note()).isNull();
    }

    @Test
    void 한_번도_안_쓴_IDENTITY_는_시작값부터() {
        SequenceSync.Value v = SequenceSync.choose(null, b(1000), b(1), null);
        assertThat(v.called()).isFalse();
        assertThat(v.next(b(1))).isEqualTo(b(1000));
    }

    @Test
    void 대상_MAX_가_앞서면_대상_값을_쓰고_알린다() {
        SequenceSync.Value v = SequenceSync.choose(b(50), b(1), b(1), b(70));
        assertThat(v.next(b(1))).isEqualTo(b(71));
        assertThat(v.note()).contains("MAX 70");
        SequenceSync.Value never = SequenceSync.choose(null, b(1), b(1), b(5));
        assertThat(never.next(b(1))).isEqualTo(b(6));
    }

    @Test
    void 감소하는_IDENTITY() {
        assertThat(SequenceSync.choose(b(-10), b(-1), b(-1), b(-8)).next(b(-1))).isEqualTo(b(-11));
        assertThat(SequenceSync.choose(b(-10), b(-1), b(-1), b(-15)).next(b(-1))).isEqualTo(b(-16));
    }

    @Test
    void 원천_이름은_대괄호로_감싼다() {
        assertThat(SequenceSync.bracket("dbo", "a]b")).isEqualTo("[dbo].[a]]b]");
    }

    @Test
    void 결과_표에_단계별_시간과_다운타임() {
        String s = CutoverRunner.summary(List.of(
                new CutoverRunner.Step(1, "마지막 반영", "DONE", 5100, "반영 대기 0"),
                new CutoverRunner.Step(2, "PK 없는 테이블 재적재", "SKIPPED", 0, "PK 없는 테이블 없음")), 6800, null);
        assertThat(s).startsWith("전환 끝").contains("1 마지막 반영").contains("완료").contains("5.1초").contains("건너뜀")
                .endsWith("소요 시간(예상 다운타임) 6.8초");
        String f = CutoverRunner.summary(List.of(), 1000, "검증 불일치");
        assertThat(f).startsWith("전환 멈춤: 검증 불일치").contains("FAILED").contains("kdms cutover 를 다시");
    }

    @Test
    void 표시_폭은_한글을_두_칸으로() {
        assertThat(CutoverRunner.pad("검증", 6)).isEqualTo("검증  ");
        assertThat(CutoverRunner.pad("FK", 6)).isEqualTo("FK    ");
    }
}
