package dowob.xyz.stockwebv2.trading.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DayPnlCalculator} 的單元測試：今日價格損益恆等式。
 *
 * <p>核心是性質測試：右式 {@code Q1×L − Q0×C − ΣB×pb + ΣS×ps} 必須與 D-12 三段分解
 * {@code (Q0−ΣS)(L−C) + ΣB(L−pb) + ΣS(ps−C)} 逐值相等，方法名含 {@code threeTerm} 者即為此類。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("今日價格損益恆等式")
class DayPnlCalculatorTest {

    private static final OffsetDateTime TODAY = OffsetDateTime.parse("2026-09-04T09:30:00-04:00");

    @Test
    @DisplayName("昨日即持有、今日無成交：損益 = 持股 × (最新價 − 前收)，分母 = 前收時市值")
    void heldSinceYesterdayOnly() {
        Optional<DayPnl> result = DayPnlCalculator.calculate(
            bd("10"), bd("200"), bd("190"), List.of(), List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("100");
        assertThat(result.get().basis()).isEqualByComparingTo("1900");
        assertThat(result.get().ratio()).isEqualByComparingTo("0.05263158");
    }

    @Test
    @DisplayName("恆等式與 D-12 三段分解逐值相等：買 5 賣 3")
    void identityEqualsThreeTermDecomposition() {
        // Q0 10；今日買 5@195、賣 3@205；Q1 = 12
        List<DayFill> fills = List.of(buy("5", "195"), sell("3", "205"));

        Optional<DayPnl> result = DayPnlCalculator.calculate(bd("12"), bd("200"), bd("190"), fills, List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("140");
        assertThat(result.get().pnl().compareTo(threeTerm(bd("10"), bd("200"), bd("190"), fills))).isZero();
    }

    @Test
    @DisplayName("三段分解成立：賣出量大於期初持股（Q0 − ΣS 為負，今天買的也被賣掉）")
    void threeTermIdentityHoldsWhenSellsExceedOpeningQuantity() {
        // Q0 2；今日買 6@195、賣 5@210；Q1 = 3；Q0 − ΣS = −3
        List<DayFill> fills = List.of(buy("6", "195"), sell("5", "210"));

        Optional<DayPnl> result = DayPnlCalculator.calculate(bd("3"), bd("200"), bd("190"), fills, List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("100");
        assertThat(result.get().pnl().compareTo(threeTerm(bd("2"), bd("200"), bd("190"), fills))).isZero();
    }

    @Test
    @DisplayName("三段分解成立：只有賣出（部分賣出）")
    void threeTermIdentityHoldsForSellOnlyDay() {
        // Q0 10；今日分兩筆賣出 4@120、6@125；Q1 = 0
        List<DayFill> fills = List.of(sell("4", "120"), sell("6", "125"));

        Optional<DayPnl> result = DayPnlCalculator.calculate(bd("0"), bd("118.5"), bd("110"), fills, List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl().compareTo(threeTerm(bd("10"), bd("118.5"), bd("110"), fills))).isZero();
    }

    @Test
    @DisplayName("三段分解成立：只有買進，且價格含小數")
    void threeTermIdentityHoldsForBuyOnlyDay() {
        // Q0 7.5；今日買 2.25@101.125、0.5@99.875；Q1 = 10.25
        List<DayFill> fills = List.of(buy("2.25", "101.125"), buy("0.5", "99.875"));

        Optional<DayPnl> result = DayPnlCalculator.calculate(bd("10.25"), bd("103.4"), bd("100.2"), fills, List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl().compareTo(threeTerm(bd("7.5"), bd("103.4"), bd("100.2"), fills))).isZero();
    }

    @Test
    @DisplayName("今天才首次買入：不需要前收，照 D-12 今日買入項計算，分母為買入成本（D-26）")
    void firstBoughtTodayIsComputedWithoutPreviousClose() {
        Optional<DayPnl> result = DayPnlCalculator.calculate(
            bd("2"), bd("510"), null, List.of(buy("2", "500")), List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("20");
        assertThat(result.get().basis()).isEqualByComparingTo("1000");
        assertThat(result.get().ratio()).isEqualByComparingTo("0.02");
    }

    @Test
    @DisplayName("今天全數賣出：用前收計算賣出項，分母為前收時市值")
    void soldAllTodayUsesPreviousClose() {
        Optional<DayPnl> result = DayPnlCalculator.calculate(
            bd("0"), bd("125"), bd("110"), List.of(sell("4", "120")), List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("40");
        assertThat(result.get().basis()).isEqualByComparingTo("440");
    }

    @Test
    @DisplayName("當沖來回（期初為零、期末為零）：不需要前收")
    void dayTradeRoundTripNeedsNoPreviousClose() {
        Optional<DayPnl> result = DayPnlCalculator.calculate(
            bd("0"), bd("101"), null, List.of(buy("10", "100"), sell("10", "103")), List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("30");
        assertThat(result.get().basis()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("完全沒有最新價：無法計算")
    void missingLatestPriceYieldsEmpty() {
        assertThat(DayPnlCalculator.calculate(bd("10"), null, bd("190"), List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("昨日即持有的股數卻無前收：無法計算（D-17 收窄後仍保留的情形）")
    void heldSinceYesterdayWithoutPreviousCloseYieldsEmpty() {
        assertThat(DayPnlCalculator.calculate(bd("10"), bd("200"), null, List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("期初持股反推為負（補登時序矛盾的帳本）：不硬算")
    void negativeOpeningQuantityYieldsEmpty() {
        // Q1 0、今日只買 10 沒賣 → Q0 = −10
        assertThat(DayPnlCalculator.calculate(
            bd("0"), bd("200"), bd("190"), List.of(buy("10", "195")), List.of())).isEmpty();
    }

    @Test
    @DisplayName("非現金股數變動（拆股擴充點）自期初持股反推中扣除")
    void shareAdjustmentIsSubtractedFromOpeningQuantity() {
        // Q1 20，其中 10 股來自拆股（無現金流）→ Q0 = 10
        Optional<DayPnl> result = DayPnlCalculator.calculate(
            bd("20"), bd("100"), bd("190"), List.of(), List.of(new ShareAdjustment(bd("10"))));

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("100");
        assertThat(result.get().basis()).isEqualByComparingTo("1900");
    }

    @Test
    @DisplayName("分母為零：損益為零、比率為 null（不除以零）")
    void zeroBasisHasNullRatio() {
        Optional<DayPnl> result = DayPnlCalculator.calculate(bd("0"), bd("100"), bd("190"), List.of(), List.of());

        assertThat(result).isPresent();
        assertThat(result.get().pnl()).isEqualByComparingTo("0");
        assertThat(result.get().basis()).isEqualByComparingTo("0");
        assertThat(result.get().ratio()).isNull();
    }

    /**
     * D-12 三段分解，獨立於被測的右式實作。
     *
     * @param q0     交易日起點持股
     * @param latest 最新價
     * @param close  前收
     * @param fills  今日成交
     * @return {@code (Q0−ΣS)(L−C) + ΣB(L−pb) + ΣS(ps−C)}
     */
    private static BigDecimal threeTerm(BigDecimal q0, BigDecimal latest, BigDecimal close, List<DayFill> fills) {
        BigDecimal soldQuantity = BigDecimal.ZERO;
        BigDecimal boughtTerm = BigDecimal.ZERO;
        BigDecimal soldTerm = BigDecimal.ZERO;
        for (DayFill fill : fills) {
            if (fill.type() == TradeType.BUY) {
                boughtTerm = boughtTerm.add(fill.quantity().multiply(latest.subtract(fill.price())));
            } else {
                soldQuantity = soldQuantity.add(fill.quantity());
                soldTerm = soldTerm.add(fill.quantity().multiply(fill.price().subtract(close)));
            }
        }
        BigDecimal heldTerm = q0.subtract(soldQuantity).multiply(latest.subtract(close));
        return heldTerm.add(boughtTerm).add(soldTerm);
    }

    private static DayFill buy(String quantity, String price) {
        return new DayFill(1L, TradeType.BUY, bd(quantity), bd(price), TODAY);
    }

    private static DayFill sell(String quantity, String price) {
        return new DayFill(1L, TradeType.SELL, bd(quantity), bd(price), TODAY);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
