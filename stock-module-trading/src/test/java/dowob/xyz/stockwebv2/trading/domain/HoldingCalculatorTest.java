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
