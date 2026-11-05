package dowob.xyz.stockwebv2.trading.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShareAdjustmentCalculator} 單元測試：依事件日持股換算拆股 / 合股的股數變動（D-08）。
 *
 * <p>數字對照 RESEARCH Q2.1 手算表。
 *
 * @author Yuan
 * @version 1.0
 */
class ShareAdjustmentCalculatorTest {

    @Test
    void forwardSplitFourForOneAddsThreeTimesTheHolding() {
        assertThat(ShareAdjustmentCalculator.splitDelta(bd("100"), 4, 1)).isEqualByComparingTo("300");
    }

    @Test
    void reverseSplitOneForTenKeepsFractionalShares() {
        assertThat(ShareAdjustmentCalculator.splitDelta(bd("105"), 1, 10)).isEqualByComparingTo("-94.5");
    }

    @Test
    void reverseSplitOneForThreeTruncatesNewQuantityInsteadOfCreatingShares() {
        BigDecimal delta = ShareAdjustmentCalculator.splitDelta(bd("100"), 1, 3);

        assertThat(delta).isEqualByComparingTo("-66.66666667");
        assertThat(bd("100").add(delta)).isEqualByComparingTo("33.33333333");
    }

    @Test
    void forwardSplitOnFractionalHolding() {
        assertThat(ShareAdjustmentCalculator.splitDelta(bd("0.5"), 2, 1)).isEqualByComparingTo("0.5");
    }

    @Test
    void resultScaleNeverExceedsEight() {
        assertThat(ShareAdjustmentCalculator.splitDelta(bd("100"), 1, 3).scale()).isLessThanOrEqualTo(8);
        assertThat(ShareAdjustmentCalculator.splitDelta(bd("7"), 2, 7).scale()).isLessThanOrEqualTo(8);
    }

    @Test
    void nullQuantityIsRejected() {
        assertThatThrownBy(() -> ShareAdjustmentCalculator.splitDelta(null, 2, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveQuantityIsRejected() {
        assertThatThrownBy(() -> ShareAdjustmentCalculator.splitDelta(bd("0"), 2, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShareAdjustmentCalculator.splitDelta(bd("-1"), 2, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveRatioIsRejected() {
        assertThatThrownBy(() -> ShareAdjustmentCalculator.splitDelta(bd("100"), 0, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShareAdjustmentCalculator.splitDelta(bd("100"), 2, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalRatioIsRejected() {
        assertThatThrownBy(() -> ShareAdjustmentCalculator.splitDelta(bd("100"), 3, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
