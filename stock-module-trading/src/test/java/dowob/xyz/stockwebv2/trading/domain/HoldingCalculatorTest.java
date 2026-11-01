package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldingCalculatorTest {

    private final HoldingCalculator calculator = new HoldingCalculator();

    @Test
    void buyCreatesHoldingIncludingFeeInAverageCost() {
        Holding result = calculator.applyBuy(null, 1L, 10L, bd("10"), bd("100"), bd("5"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("10");
        assertThat(result.avgCost()).isEqualByComparingTo("100.50000000");
        assertThat(result.realizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    void buyAddsQuantityAndRecalculatesWeightedAverageCost() {
        Holding existing = holding(bd("10"), bd("100"), bd("0"));

        Holding result = calculator.applyBuy(existing, 1L, 10L, bd("5"), bd("130"), bd("2.50"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("15");
        assertThat(result.avgCost()).isEqualByComparingTo("110.16666667");
        assertThat(result.realizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    void sellReducesQuantityAndAddsRealizedPnlAfterFee() {
        Holding existing = holding(bd("10"), bd("100.50"), bd("0"));

        Holding result = calculator.applySell(existing, bd("4"), bd("120"), bd("1"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("6");
        assertThat(result.avgCost()).isEqualByComparingTo("100.50");
        assertThat(result.realizedPnl()).isEqualByComparingTo("77.00");
    }

    @Test
    void fullSellZerosAverageCostButKeepsRealizedPnl() {
        Holding existing = holding(bd("3"), bd("50"), bd("10"));

        Holding result = calculator.applySell(existing, bd("3"), bd("60"), bd("2"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("0");
        assertThat(result.avgCost()).isEqualByComparingTo("0");
        assertThat(result.realizedPnl()).isEqualByComparingTo("38");
    }

    @Test
    void oversellFails() {
        Holding existing = holding(bd("3"), bd("50"), bd("0"));

        assertThatThrownBy(() -> calculator.applySell(existing, bd("4"), bd("60"), bd("0"), now()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_INSUFFICIENT_HOLDING);
    }

    @Test
    void stockDividendKeepsTotalCostAndDilutesAverageCost() {
        Holding existing = holding(bd("1000"), bd("100"), bd("12.5"));
        OffsetDateTime later = OffsetDateTime.parse("2026-10-30T08:00:00Z");

        Holding result = calculator.applyShareAdjustment(existing, bd("50"), later);

        assertThat(result.totalQuantity()).isEqualByComparingTo("1050");
        assertThat(result.avgCost()).isEqualByComparingTo("95.23809524");
        assertThat(result.realizedPnl()).isEqualByComparingTo("12.5");
        assertThat(result.id()).isEqualTo(99L);
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.assetId()).isEqualTo(10L);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.lastUpdated()).isEqualTo(later);
    }

    @Test
    void stockDividendCostBasisDiffersOnlyByScaleEightRounding() {
        Holding existing = holding(bd("1000"), bd("100"), bd("0"));

        Holding result = calculator.applyShareAdjustment(existing, bd("50"), now());

        BigDecimal costBasis = result.totalQuantity().multiply(result.avgCost());
        assertThat(costBasis.subtract(bd("100000")).abs()).isLessThan(bd("0.00001"));
    }

    @Test
    void forwardSplitMultipliesQuantityAndDividesAverageCost() {
        Holding existing = holding(bd("100"), bd("400"), bd("0"));

        Holding result = calculator.applyShareAdjustment(existing, bd("300"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("400");
        assertThat(result.avgCost()).isEqualByComparingTo("100.00000000");
    }

    @Test
    void reverseSplitKeepsFractionalQuantityAndRaisesAverageCost() {
        Holding existing = holding(bd("105"), bd("10"), bd("0"));

        Holding result = calculator.applyShareAdjustment(existing, bd("-94.5"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("10.5");
        assertThat(result.avgCost()).isEqualByComparingTo("100.00000000");
    }

    @Test
    void reverseSplitOneForThreeUsesTruncatedQuantityForAverageCost() {
        Holding existing = holding(bd("100"), bd("30"), bd("0"));

        Holding result = calculator.applyShareAdjustment(existing, bd("-66.66666667"), now());

        assertThat(result.totalQuantity()).isEqualByComparingTo("33.33333333");
        assertThat(result.avgCost()).isEqualByComparingTo("90.00000009");
    }

    @Test
    void shareAdjustmentWithoutHoldingFails() {
        assertThatThrownBy(() -> calculator.applyShareAdjustment(null, bd("50"), now()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_INSUFFICIENT_HOLDING);
    }

    @Test
    void shareAdjustmentOnZeroHoldingFails() {
        Holding existing = holding(bd("0"), bd("0"), bd("5"));

        assertThatThrownBy(() -> calculator.applyShareAdjustment(existing, bd("50"), now()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_INSUFFICIENT_HOLDING);
    }

    @Test
    void shareAdjustmentLeavingNegativeHoldingFails() {
        Holding existing = holding(bd("50"), bd("10"), bd("0"));

        assertThatThrownBy(() -> calculator.applyShareAdjustment(existing, bd("-300"), now()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_INSUFFICIENT_HOLDING);
    }

    @Test
    void shareAdjustmentLeavingExactlyZeroHoldingFails() {
        Holding existing = holding(bd("50"), bd("10"), bd("0"));

        assertThatThrownBy(() -> calculator.applyShareAdjustment(existing, bd("-50"), now()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_INSUFFICIENT_HOLDING);
    }

    private Holding holding(BigDecimal quantity, BigDecimal avgCost, BigDecimal realizedPnl) {
        return new Holding(99L, 1L, 10L, quantity, avgCost, realizedPnl, 2, now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.parse("2026-05-30T12:00:00Z");
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
