package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2ETest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 投資組合 summary / holdings 在真實交易、真實現金帳本與基準幣切換下的 REST 層 E2E
 * （PORT-09 ~ PORT-12、CASH-02、TRAD-10）。
 *
 * <p>契約見 {@code ai-docs/trading-portfolio-contract.md}：summary 的金額欄位以使用者基準幣表示，
 * 現金是「入出金 ± 全部交易現金流」推導的原幣餘額（可為負，D-01 / D-08），
 * {@code totalEquity = totalMarketValue + cash}，快取鍵含基準幣、入出金與交易寫入在 commit 後立即失效。</p>
 *
 * <p>數字取自 V2 seed 匯率：USD→TWD 32.418、TWD→USD 0.030847（直接列）；行情以 SQL 種在 {@code market_prices}
 * （E2E profile 沒有背景 tick）。情境標的：TSLA（USD、Auto）與 2330.TW（TWD、Tech）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Portfolio Base Currency E2E")
class PortfolioBaseCurrencyE2E extends AbstractPortfolioE2ETest {

    /**
     * 建立情境：入金 USD 5000 與 TWD 100000，買入 TSLA 10 股@100（USD）與 2330.TW 2 股@900（TWD），
     * 並把兩檔最新價種為 120 / 1000。
     *
     * <p>餘額：USD 5000 − 1000 = 4000、TWD 100000 − 1800 = 98200。</p>
     *
     * @param token access token
     * @throws Exception MockMvc 失敗時
     */
    private void givenMixedCurrencyPortfolio(String token) throws Exception {
        recordCashEntry(token, "DEPOSIT", "USD", "5000");
        recordCashEntry(token, "DEPOSIT", "TWD", "100000");
        JsonNode tesla = recordTrade(token, "TSLA", "BUY", "10", "100");
        JsonNode tsmc = recordTrade(token, "2330.TW", "BUY", "2", "900");
        // TradeDto.currency 取自標的幣別（TRAD-10），不是使用者基準幣。
        assertThat(tesla.get("currency").asText()).isEqualTo("USD");
        assertThat(tsmc.get("currency").asText()).isEqualTo("TWD");
        seedLatestPrice("TSLA", "120");
        seedLatestPrice("2330.TW", "1000");
    }

    private static BigDecimal weightSum(JsonNode rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            sum = sum.add(row.get("weight").decimalValue());
        }
        return sum;
    }

    private static JsonNode sliceOf(JsonNode summary, String category) {
        for (JsonNode slice : summary.get("allocation")) {
            if (category.equals(slice.get("category").asText())) {
                return slice;
            }
        }
        return null;
    }

    private static JsonNode sectorOf(JsonNode summary, String sector) {
        for (JsonNode row : summary.get("sectorAllocation")) {
            if (sector.equals(row.get("sector").asText())) {
                return row;
            }
        }
        return null;
    }

    @Test
    @DisplayName("混合幣別（預設 TWD 基準幣）：現金、市值、總資產與帳本一致，配置與產業權重各自加總為 1，持倉與交易帶幣別")
    void mixedCurrencyPortfolioIsConsistentWithTheLedgerInTwdBase() throws Exception {
        var session = auth.register("pf-e2e-mixed@example.com", "pfe2emixed", PASSWORD);
        String token = session.accessToken();
        givenMixedCurrencyPortfolio(token);

        JsonNode summary = summaryOf(token);

        assertThat(summary.get("baseCurrency").asText()).isEqualTo("TWD");
        assertThat(summary.get("holdingCount").asInt()).isEqualTo(2);
        assertThat(summary.get("fxExcludedCount").asInt()).isZero();
        assertThat(summary.get("unconvertedCurrencies")).isEmpty();
        // 原幣現金餘額 = 帳本入金 − 買入成本（兩幣別各自獨立）
        assertThat(balanceOf(summary, "USD").get("amount").decimalValue()).isEqualByComparingTo("4000");
        assertThat(balanceOf(summary, "TWD").get("amount").decimalValue()).isEqualByComparingTo("98200");
        // 4000 USD × 32.418 + 98200 TWD
        assertThat(summary.get("cash").decimalValue()).isEqualByComparingTo("227872");
        // 10 × 120 USD × 32.418 + 2 × 1000 TWD
        assertThat(summary.get("totalMarketValue").decimalValue()).isEqualByComparingTo("40901.6");
        // 10 × 100 USD × 32.418 + 2 × 900 TWD
        assertThat(summary.get("totalCostBasis").decimalValue()).isEqualByComparingTo("34218");
        assertThat(summary.get("unrealizedPnl").decimalValue()).isEqualByComparingTo("6683.6");
        assertThat(summary.get("totalEquity").decimalValue()).isEqualByComparingTo("268773.6");
        assertThat(summary.get("totalEquity").decimalValue()).isEqualByComparingTo(
            summary.get("totalMarketValue").decimalValue().add(summary.get("cash").decimalValue()));

        // 資產配置：持倉類別 + 正現金，產業不含現金；兩者權重各自加總為 1（8 位小數四捨五入的誤差內）。
        assertThat(sliceOf(summary, "STOCK").get("value").decimalValue()).isEqualByComparingTo("40901.6");
        assertThat(sliceOf(summary, "CASH").get("value").decimalValue()).isEqualByComparingTo("227872");
        assertThat(weightSum(summary.get("allocation")).doubleValue()).isCloseTo(1.0, within(1e-6));
        assertThat(summary.get("sectorAllocation")).hasSize(2);
        assertThat(sectorOf(summary, "Auto").get("holdingCount").asInt()).isEqualTo(1);
        assertThat(sectorOf(summary, "Tech").get("holdingCount").asInt()).isEqualTo(1);
        assertThat(weightSum(summary.get("sectorAllocation")).doubleValue()).isCloseTo(1.0, within(1e-6));

        // holdings：金額為原幣，另帶 currency / assetType / sector 與折算成基準幣的市值。
        JsonNode holdings = holdingsOf(token);
        assertThat(holdings).hasSize(2);
        JsonNode tesla = holdingOf(holdings, "TSLA");
        assertThat(tesla.get("currency").asText()).isEqualTo("USD");
        assertThat(tesla.get("assetType").asText()).isEqualTo("STOCK");
        assertThat(tesla.get("sector").asText()).isEqualTo("Auto");
        assertThat(tesla.get("marketValue").decimalValue()).isEqualByComparingTo("1200");
        assertThat(tesla.get("baseCurrency").asText()).isEqualTo("TWD");
        assertThat(tesla.get("marketValueInBase").decimalValue()).isEqualByComparingTo("38901.6");
        JsonNode tsmc = holdingOf(holdings, "2330.TW");
        assertThat(tsmc.get("currency").asText()).isEqualTo("TWD");
        assertThat(tsmc.get("assetType").asText()).isEqualTo("STOCK");
        assertThat(tsmc.get("marketValue").decimalValue()).isEqualByComparingTo("2000");
        assertThat(tsmc.get("marketValueInBase").decimalValue()).isEqualByComparingTo("2000");

        // GET /trades 的每一列也帶各自標的的幣別。
        mockMvc.perform(get("/api/v1/trades").with(bearerToken(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items[?(@.symbol=='TSLA')].currency", hasItem("USD")))
            .andExpect(jsonPath("$.data.items[?(@.symbol=='2330.TW')].currency", hasItem("TWD")));
    }

    @Test
    @DisplayName("PATCH 切換基準幣後 summary / holdings 立即換算（快取鍵含基準幣），原幣餘額不變，切回後數字還原")
    void switchingBaseCurrencyChangesSummaryAndHoldingsImmediately() throws Exception {
        var session = auth.register("pf-e2e-switch@example.com", "pfe2eswitch", PASSWORD);
        String token = session.accessToken();
        givenMixedCurrencyPortfolio(token);

        // 先讀一次讓 TWD 版 summary 進快取；若快取鍵不含基準幣，切換後會拿到這份舊值。
        assertThat(summaryOf(token).get("totalEquity").decimalValue()).isEqualByComparingTo("268773.6");

        switchBaseCurrency(token, "USD");

        JsonNode usd = summaryOf(token);
        assertThat(usd.get("baseCurrency").asText()).isEqualTo("USD");
        // 10 × 120 USD + 2 × 1000 TWD × 0.030847
        assertThat(usd.get("totalMarketValue").decimalValue()).isEqualByComparingTo("1261.694");
        // 4000 USD + 98200 TWD × 0.030847
        assertThat(usd.get("cash").decimalValue()).isEqualByComparingTo("7029.1754");
        assertThat(usd.get("totalEquity").decimalValue()).isEqualByComparingTo("8290.8694");
        // 各幣別原幣餘額與基準幣無關。
        assertThat(balanceOf(usd, "USD").get("amount").decimalValue()).isEqualByComparingTo("4000");
        assertThat(balanceOf(usd, "TWD").get("amount").decimalValue()).isEqualByComparingTo("98200");
        assertThat(weightSum(usd.get("allocation")).doubleValue()).isCloseTo(1.0, within(1e-6));

        JsonNode holdings = holdingsOf(token);
        assertThat(holdingOf(holdings, "TSLA").get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(holdingOf(holdings, "TSLA").get("marketValueInBase").decimalValue()).isEqualByComparingTo("1200");
        assertThat(holdingOf(holdings, "2330.TW").get("marketValueInBase").decimalValue())
            .isEqualByComparingTo("61.694");
        // 原幣欄位不隨基準幣改變。
        assertThat(holdingOf(holdings, "2330.TW").get("marketValue").decimalValue()).isEqualByComparingTo("2000");

        switchBaseCurrency(token, "TWD");

        JsonNode twd = summaryOf(token);
        assertThat(twd.get("baseCurrency").asText()).isEqualTo("TWD");
        assertThat(twd.get("totalEquity").decimalValue()).isEqualByComparingTo("268773.6");
    }

    @Test
    @DisplayName("沒有入金就買入照常成交（交易從不被現金擋下），該幣別現金為負（D-01 / D-08），配置沒有 CASH 一塊")
    void buyingWithoutDepositSucceedsAndLeavesNegativeCash() throws Exception {
        var session = auth.register("pf-e2e-nodeposit@example.com", "pfe2enodeposit", PASSWORD);
        String token = session.accessToken();

        JsonNode trade = recordTrade(token, "TSLA", "BUY", "10", "100");
        assertThat(trade.get("currency").asText()).isEqualTo("USD");
        seedLatestPrice("TSLA", "100");

        JsonNode summary = summaryOf(token);

        assertThat(balanceOf(summary, "USD").get("amount").decimalValue()).isEqualByComparingTo("-1000");
        // -1000 USD × 32.418
        assertThat(summary.get("cash").decimalValue()).isEqualByComparingTo("-32418");
        assertThat(summary.get("totalMarketValue").decimalValue()).isEqualByComparingTo("32418");
        assertThat(summary.get("totalEquity").decimalValue()).isEqualByComparingTo("0");
        assertThat(sliceOf(summary, "CASH")).isNull();
        assertThat(summary.get("allocation")).hasSize(1);
        assertThat(sliceOf(summary, "STOCK").get("weight").decimalValue()).isEqualByComparingTo("1");

        getCashEntries(token, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(0)));

        // 原幣餘額不隨基準幣改變；折算後的現金與總資產隨之換算。
        switchBaseCurrency(token, "USD");
        JsonNode usd = summaryOf(token);
        assertThat(balanceOf(usd, "USD").get("amount").decimalValue()).isEqualByComparingTo("-1000");
        assertThat(usd.get("cash").decimalValue()).isEqualByComparingTo("-1000");
        assertThat(usd.get("totalEquity").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("入金、出金、沖銷與交易 commit 後，下一次 summary 讀取立即反映（快取在寫入 commit 之後失效）")
    void cashEntriesReversalsAndTradesAreReflectedInTheNextSummaryRead() throws Exception {
        var session = auth.register("pf-e2e-cache@example.com", "pfe2ecache", PASSWORD);
        String token = session.accessToken();

        // 空帳戶：真實的 0、沒有餘額列、沒有配置。這次讀取同時讓 summary 進快取。
        JsonNode empty = summaryOf(token);
        assertThat(empty.get("baseCurrency").asText()).isEqualTo("TWD");
        assertThat(empty.get("holdingCount").asInt()).isZero();
        assertThat(empty.get("cash").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("totalEquity").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("cashBalances")).isEmpty();
        assertThat(empty.get("allocation")).isEmpty();
        assertThat(empty.get("sectorAllocation")).isEmpty();

        recordCashEntry(token, "DEPOSIT", "TWD", "50000");
        JsonNode afterDeposit = summaryOf(token);
        assertThat(afterDeposit.get("cash").decimalValue()).isEqualByComparingTo("50000");
        assertThat(balanceOf(afterDeposit, "TWD").get("amount").decimalValue()).isEqualByComparingTo("50000");
        assertThat(sliceOf(afterDeposit, "CASH").get("weight").decimalValue()).isEqualByComparingTo("1");

        JsonNode withdrawal = recordCashEntry(token, "WITHDRAWAL", "TWD", "10000");
        assertThat(summaryOf(token).get("cash").decimalValue()).isEqualByComparingTo("40000");

        // 沖銷該筆出金：反向分錄自然抵銷，餘額立即回到 50000。
        dataOf(postCashEntry(token, UUID.randomUUID().toString(),
            reversalBody("DEPOSIT", "TWD", "10000", withdrawal.get("id").asText()))
            .andExpect(status().isOk()));
        JsonNode afterReversal = summaryOf(token);
        assertThat(afterReversal.get("cash").decimalValue()).isEqualByComparingTo("50000");
        assertThat(balanceOf(afterReversal, "TWD").get("amount").decimalValue()).isEqualByComparingTo("50000");

        // 另一幣別的入金：USD 100 × 32.418 = 3241.8 併入基準幣現金；沖銷後立即移除。
        JsonNode usdDeposit = recordCashEntry(token, "DEPOSIT", "USD", "100");
        assertThat(summaryOf(token).get("cash").decimalValue()).isEqualByComparingTo("53241.8");
        dataOf(postCashEntry(token, UUID.randomUUID().toString(),
            reversalBody("WITHDRAWAL", "USD", "100", usdDeposit.get("id").asText()))
            .andExpect(status().isOk()));
        assertThat(summaryOf(token).get("cash").decimalValue()).isEqualByComparingTo("50000");

        // 交易寫入同樣使 summary 快取失效：持倉數與現金立即改變（1 股 × 900 TWD）。
        seedLatestPrice("2330.TW", "1000");
        recordTrade(token, "2330.TW", "BUY", "1", "900");
        JsonNode afterTrade = summaryOf(token);
        assertThat(afterTrade.get("holdingCount").asInt()).isEqualTo(1);
        assertThat(balanceOf(afterTrade, "TWD").get("amount").decimalValue()).isEqualByComparingTo("49100");
        assertThat(afterTrade.get("totalMarketValue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(afterTrade.get("totalEquity").decimalValue()).isEqualByComparingTo("50100");
    }

    @Test
    @DisplayName("summary 與 holdings 未認證回 401")
    void portfolioReadsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/summary"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)));
        mockMvc.perform(get("/api/v1/portfolio/holdings"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)));
    }
}
