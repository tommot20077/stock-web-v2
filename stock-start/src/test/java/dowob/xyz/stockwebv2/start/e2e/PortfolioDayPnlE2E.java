package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2ETest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;

/**
 * 今日價格損益（PORT-11）的 REST 層 E2E，聚焦「沒有前收」時的 null / 排除語意。
 *
 * <p>語意以 {@code PortfolioQueryService} 與 {@code DayPnlCalculator} 的實作為準（不是猜的）：</p>
 * <ul>
 *   <li>昨日即持有、卻沒有前收列 → 該檔 {@code dayPricePnl} / {@code dayPricePnlRatio} / {@code previousClose} 為 null，
 *       summary 以 {@code dayPnlExcludedCount} 回報，且<strong>不</strong>把缺資料當 0；</li>
 *   <li>今天首次買入、沒有前收列 → 照今日買入項計算（D-26），{@code previousClose} 仍為 null；</li>
 *   <li>summary 的總額只加總可得者；<strong>全部不可得</strong>時為 null（缺資料）；<strong>沒有任何候選</strong>時為真實的 0，
 *       此時 ratio 為 null、排除數為 0。</li>
 * </ul>
 *
 * <p>行情以 SQL 種在 {@code market_prices}：前收列在 26 小時前、最新列在 1 分鐘前。三檔皆為 USD，基準幣切為 USD 以免混入匯率。
 * 所有 null 欄位都要求「欄位存在且為 null」（本專案不省略 null 欄位），不只是「沒有值」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Portfolio Day PnL E2E")
class PortfolioDayPnlE2E extends AbstractPortfolioE2ETest {

    /**
     * 26 小時前的 ISO-8601 時間，作為「昨日成交」的 {@code executedAt}。
     *
     * @return ISO-8601 字串
     */
    private static String yesterday() {
        return OffsetDateTime.now().minusHours(26).toString();
    }

    @Test
    @DisplayName("有前收、今日首買無前收、昨日持有卻無前收三種持倉並存：各自的欄位語意與 summary 總額 / 比率 / 排除數")
    void mixedPreviousCloseAvailabilityYieldsPerHoldingNullsAndPartialTotals() throws Exception {
        var session = auth.register("daypnl-e2e-mixed@example.com", "daypnle2emixed", PASSWORD);
        String token = session.accessToken();
        switchBaseCurrency(token, "USD");
        recordTrade(token, "MSFT", "BUY", "10", "150", yesterday());
        recordTrade(token, "META", "BUY", "2", "500");
        recordTrade(token, "AMZN", "BUY", "3", "180", yesterday());
        seedPrices("MSFT", "190", "200");
        seedPrices("META", null, "510");
        seedPrices("AMZN", null, "190");

        JsonNode holdings = holdingsOf(token);
        assertThat(holdings).hasSize(3);

        // MSFT：有前收 190、最新 200，昨日持有 10 股 → 10 × (200 − 190)，分母 10 × 190
        JsonNode msft = holdingOf(holdings, "MSFT");
        assertThat(msft.get("previousClose").decimalValue()).isEqualByComparingTo("190");
        assertThat(msft.get("dayPricePnl").decimalValue()).isEqualByComparingTo("100");
        assertThat(msft.get("dayPricePnlRatio").decimalValue()).isEqualByComparingTo("0.05263158");

        // META：今天首次買入、沒有前收列 → 照今日買入項計算 2 × (510 − 500)，previousClose 為 null
        JsonNode meta = holdingOf(holdings, "META");
        assertThat(meta.get("previousClose").isNull()).isTrue();
        assertThat(meta.get("dayPricePnl").decimalValue()).isEqualByComparingTo("20");
        assertThat(meta.get("dayPricePnlRatio").decimalValue()).isEqualByComparingTo("0.02");

        // AMZN：昨日即持有且沒有前收 → 三欄皆為 null（欄位存在），不是 0
        JsonNode amzn = holdingOf(holdings, "AMZN");
        assertThat(amzn.get("previousClose").isNull()).isTrue();
        assertThat(amzn.get("dayPricePnl").isNull()).isTrue();
        assertThat(amzn.get("dayPricePnlRatio").isNull()).isTrue();
        // 估值本身不受影響：3 股 × 最新價 190
        assertThat(amzn.get("marketValue").decimalValue()).isEqualByComparingTo("570");

        // summary：只加總可得者 MSFT 100 + META 20 = 120，分母 1900 + 1000；AMZN 以排除數回報
        JsonNode summary = summaryOf(token);
        assertThat(summary.get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(summary.get("dayPricePnl").decimalValue()).isEqualByComparingTo("120");
        assertThat(summary.get("dayPricePnlRatio").decimalValue()).isEqualByComparingTo("0.04137931");
        assertThat(summary.get("dayPnlExcludedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("唯一的持倉昨日即持有卻沒有前收：summary 的 dayPricePnl 與 ratio 為 null（缺資料，不塞 0），排除數為 1")
    void onlyUnavailableCandidateYieldsNullTotalsAndExcludedCount() throws Exception {
        var session = auth.register("daypnl-e2e-missing@example.com", "daypnle2emissing", PASSWORD);
        String token = session.accessToken();
        switchBaseCurrency(token, "USD");
        recordTrade(token, "AMZN", "BUY", "3", "180", yesterday());
        seedPrices("AMZN", null, "190");

        JsonNode summary = summaryOf(token);

        assertThat(summary.get("holdingCount").asInt()).isEqualTo(1);
        assertThat(summary.get("dayPricePnl").isNull()).isTrue();
        assertThat(summary.get("dayPricePnlRatio").isNull()).isTrue();
        assertThat(summary.get("dayPnlExcludedCount").asInt()).isEqualTo(1);
        // 其他欄位照常：3 × 190 市值、未實現損益 3 × (190 − 180)
        assertThat(summary.get("totalMarketValue").decimalValue()).isEqualByComparingTo("570");
        assertThat(summary.get("unrealizedPnl").decimalValue()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("沒有任何持倉的使用者：dayPricePnl 是真實的 0、ratio 為 null、排除數為 0")
    void noCandidatesYieldsRealZero() throws Exception {
        var session = auth.register("daypnl-e2e-empty@example.com", "daypnle2eempty", PASSWORD);
        String token = session.accessToken();
        switchBaseCurrency(token, "USD");

        JsonNode summary = summaryOf(token);

        assertThat(summary.get("holdingCount").asInt()).isZero();
        assertThat(summary.get("dayPricePnl").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("dayPricePnlRatio").isNull()).isTrue();
        assertThat(summary.get("dayPnlExcludedCount").asInt()).isZero();
        assertThat(holdingsOf(token)).isEmpty();
    }
}
