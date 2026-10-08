package kdms.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** 지연 = 원천 마지막 커밋 시각 − 뒤처진 위치의 커밋 시각(docs/cdc.md §1) */
class LagTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 8, 12, 0, 0);

    @Test
    void 초_소수_셋째_자리() {
        assertThat(SyncRunner.lagSeconds(T.plusNanos(1_500_000_000L), T)).isEqualByComparingTo(new BigDecimal("1.500"));
    }

    @Test
    void 모르면_null_음수면_0() {
        assertThat(SyncRunner.lagSeconds(T, null)).isNull();
        assertThat(SyncRunner.lagSeconds(null, T)).isNull();
        assertThat(SyncRunner.lagSeconds(T, T.plusSeconds(3))).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
