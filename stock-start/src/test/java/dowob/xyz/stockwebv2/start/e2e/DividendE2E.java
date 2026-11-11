package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2E;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 現金股利（DIV-01 / DIV-02）的 REST 層 E2E：記股利 → summary 現金與股利收入上升 → 活動列表列出 → 一鍵沖銷 → 全部回到原值。
 *
 * <p>契約見 {@code ai-docs/trading-portfolio-contract.md}〈POST /api/v1/portfolio/dividends〉：必帶 {@code Idempotency-Key}；
 * 同 key 不同 payload 回 409 {@code DIVIDEND_IDEMPOTENCY_KEY_REUSED}；沖銷是帶 {@code reversalOfId}、值相同的另一列，
 * 聚合時反號。{@code totalPnl} 含股利收入，{@code roi = totalPnl / totalCostBasis} 公式不變因而含股利（04.2-02 裁決
 * option-recommended）；{@code dayPricePnl} 不含股利。</p>
 *
 * <p>與 {@code DividendApiIT} / {@code PortfolioDividendSummaryIT} 的分工：IT 鎖單一端點的錯誤矩陣、併發與資料列；
 * 本類別走 e2e profile 的完整應用，把「寫入 → summary（含快取失效）→ 活動列表 → 沖銷」串成使用者實際會走的一條路。
 * 基準幣切為 USD，讓股利金額不經匯率、可精確斷言。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Dividend E2E")
class DividendE2E extends AbstractPortfolioE2E {

    /** AAPL 實領 120、預扣稅 30 的股利（入帳時間由後端補現在）。 */
    private static final String AAPL_120_TAX_30 = """
        {"symbol":"AAPL","amount":120,"withholdingTax":30,"note":"Q3 dividend"}
        """;

    /**
     * 組出沖銷 AAPL 120 / 30 的請求本文。
     *
     * @param originalId 原股利 UUID
     * @return JSON 字串
     */
    private static String reversalOfAapl120(String originalId) {
        return """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"reversalOfId":"%s"}
            """.formatted(originalId);
    }

    @Test
    @DisplayName("記 AAPL 股利 120（稅 30）→ summary USD 現金 120、dividendIncome 120 → 活動列表 1 列可沖銷 → 沖銷 → 股利收入與現金歸 0、列表 2 列且原列已沖銷")
    void dividendFlowsIntoSummaryAndActivitiesAndReversalRestoresIt() throws Exception {
        var session = auth.register("dividend-e2e-flow@example.com", "dividende2eflow", PASSWORD);
        String token = session.accessToken();
        switchBaseCurrency(token, "USD");

        /* 先讀一次讓 summary 進快取：後續讀值改變證明股利寫入有在 commit 後失效快取，而不是等 TTL。 */
        JsonNode before = summaryOf(token);
        assertThat(before.get("dividendIncome").decimalValue()).isEqualByComparingTo("0");
        assertThat(before.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("0");

        String dividendId = dataOf(postDividend(token, UUID.randomUUID().toString(), AAPL_120_TAX_30)
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.id", hasLength(36)))
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data.currency", equalTo("USD")))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())).get("id").asText();

        JsonNode afterDividend = summaryOf(token);
        assertThat(balanceOf(afterDividend, "USD").get("amount").decimalValue()).isEqualByComparingTo("120");
        assertThat(afterDividend.get("cash").decimalValue()).isEqualByComparingTo("120");
        assertThat(afterDividend.get("dividendIncome").decimalValue()).isEqualByComparingTo("120");
        assertThat(afterDividend.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("30");
        assertThat(afterDividend.get("totalPnl").decimalValue()).isEqualByComparingTo("120");
        /* 股利不動股數：沒有任何交易時仍無持倉（D-03：無持倉也可記股利）。 */
        assertThat(afterDividend.get("holdingCount").asInt()).isZero();
        assertThat(holdingsOf(token)).isEmpty();

        JsonNode listed = activitiesOf(token, "type", "DIVIDEND");
        assertThat(listed.get("totalElements").asLong()).isEqualTo(1);
        JsonNode row = listed.get("items").get(0);
        assertThat(row.get("id").asText()).isEqualTo(dividendId);
        assertThat(row.get("kind").asText()).isEqualTo("CASH_DIVIDEND");
        assertThat(row.get("symbol").asText()).isEqualTo("AAPL");
        assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("120");
        assertThat(row.get("withholdingTax").decimalValue()).isEqualByComparingTo("30");
        assertThat(row.get("quantity").isNull()).isTrue();
        assertThat(row.get("reversed").asBoolean()).isFalse();
        assertThat(row.get("reversible").asBoolean()).isTrue();

        String reversalId = dataOf(postDividend(token, UUID.randomUUID().toString(), reversalOfAapl120(dividendId))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(dividendId)))
            .andExpect(jsonPath("$.data.amount", equalTo(120.0)))).get("id").asText();

        JsonNode afterReversal = summaryOf(token);
        assertThat(afterReversal.get("dividendIncome").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterReversal.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterReversal.get("totalPnl").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterReversal.get("cash").decimalValue()).isEqualByComparingTo("0");
        /* 04.2-05：有過股利活動的幣別在沖銷後以 0 元列出現。 */
        assertThat(balanceOf(afterReversal, "USD").get("amount").decimalValue()).isEqualByComparingTo("0");

        JsonNode relisted = activitiesOf(token, "type", "DIVIDEND");
        assertThat(relisted.get("totalElements").asLong()).isEqualTo(2);
        JsonNode original = entryById(relisted.get("items"), dividendId);
        JsonNode reversal = entryById(relisted.get("items"), reversalId);
        assertThat(original).isNotNull();
        assertThat(reversal).isNotNull();
        assertThat(original.get("reversed").asBoolean()).isTrue();
        assertThat(original.get("reversible").asBoolean()).isFalse();
        assertThat(original.get("reversalOfId").isNull()).isTrue();
        assertThat(reversal.get("kind").asText()).isEqualTo("CASH_DIVIDEND");
        assertThat(reversal.get("reversalOfId").asText()).isEqualTo(dividendId);
        assertThat(reversal.get("reversalOfOccurredAt").isNull()).isFalse();
        /* 沖銷列輸出原值（不反號），且本身不可再沖銷。 */
        assertThat(reversal.get("amount").decimalValue()).isEqualByComparingTo("120");
        assertThat(reversal.get("reversible").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("同 key 改 amount：409 DIVIDEND_IDEMPOTENCY_KEY_REUSED、不回射 key；缺 Idempotency-Key：400 fields['Idempotency-Key']；兩者都不新增列")
    void keyReuseAndMissingKeyAreRejectedWithoutWriting() throws Exception {
        var session = auth.register("dividend-e2e-key@example.com", "dividende2ekey", PASSWORD);
        String token = session.accessToken();
        String key = "E2E-DIVIDEND-KEY-" + UUID.randomUUID();

        postDividend(token, key, AAPL_120_TAX_30)
            .andExpect(status().isOk())
            .andExpect(apiSuccess());

        String reuseBody = postDividend(token, key, """
            {"symbol":"AAPL","amount":121,"withholdingTax":30,"note":"Q3 dividend"}
            """)
            .andExpect(status().isConflict())
            .andExpect(apiError("DIVIDEND_IDEMPOTENCY_KEY_REUSED"))
            .andReturn().getResponse().getContentAsString();
        assertThat(reuseBody).doesNotContain("E2E-DIVIDEND-KEY-");

        postDividend(token, null, AAPL_120_TAX_30)
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", notNullValue()));

        assertThat(activitiesOf(token, "type", "DIVIDEND").get("totalElements").asLong()).isEqualTo(1);
        assertThat(summaryOf(token).get("dividendIncome").decimalValue()).isPositive();
    }

    @Test
    @DisplayName("roi 含股利（04.2-02 option-recommended）：持有 10 股 @100、價格不變、股利 120 → totalPnl 120、roi 0.12；dayPricePnl 不含股利仍為 0")
    void roiIncludesDividendIncomeButDayPnlDoesNot() throws Exception {
        var session = auth.register("dividend-e2e-roi@example.com", "dividende2eroi", PASSWORD);
        String token = session.accessToken();
        switchBaseCurrency(token, "USD");
        recordTrade(token, "AAPL", "BUY", "10", "100", OffsetDateTime.now().minusHours(26).toString());
        seedPrices("AAPL", "100", "100");

        JsonNode before = summaryOf(token);
        assertThat(before.get("totalPnl").decimalValue()).isEqualByComparingTo("0");
        assertThat(before.get("roi").decimalValue()).isEqualByComparingTo("0");

        postDividend(token, UUID.randomUUID().toString(), AAPL_120_TAX_30)
            .andExpect(status().isOk());

        JsonNode summary = summaryOf(token);
        assertThat(summary.get("totalCostBasis").decimalValue()).isEqualByComparingTo("1000");
        assertThat(summary.get("unrealizedPnl").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("realizedPnl").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("dividendIncome").decimalValue()).isEqualByComparingTo("120");
        assertThat(summary.get("totalPnl").decimalValue()).isEqualByComparingTo("120");
        assertThat(summary.get("roi").decimalValue()).isEqualByComparingTo("0.12");
        assertThat(summary.get("dayPricePnl").decimalValue()).isEqualByComparingTo("0");
        /* 買入 −1000 + 股利 120：股利是可用現金的來源之一。 */
        assertThat(balanceOf(summary, "USD").get("amount").decimalValue()).isEqualByComparingTo("-880");
        /* 股利不動股數與均價。 */
        JsonNode aapl = holdingOf(holdingsOf(token), "AAPL");
        assertThat(aapl.get("totalQuantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(aapl.get("avgCost").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("另一使用者：看不到 A 的股利（活動列表與 summary），沖銷 A 的股利 → 404 DIVIDEND_NOT_FOUND 且不回射 A 的 uuid")
    void otherUserCannotSeeOrReverseDividend() throws Exception {
        var alice = auth.register("dividend-e2e-alice@example.com", "dividende2ealice", PASSWORD);
        var bob = auth.register("dividend-e2e-bob@example.com", "dividende2ebob", PASSWORD);
        String aliceDividendId = dataOf(postDividend(alice.accessToken(), UUID.randomUUID().toString(), AAPL_120_TAX_30)
            .andExpect(status().isOk())).get("id").asText();

        assertThat(activitiesOf(bob.accessToken()).get("totalElements").asLong()).isZero();
        assertThat(summaryOf(bob.accessToken()).get("dividendIncome").decimalValue()).isEqualByComparingTo("0");

        String body = postDividend(bob.accessToken(), UUID.randomUUID().toString(), reversalOfAapl120(aliceDividendId))
            .andExpect(status().isNotFound())
            .andExpect(apiError("DIVIDEND_NOT_FOUND"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(aliceDividendId);

        JsonNode aliceRow = activitiesOf(alice.accessToken(), "type", "DIVIDEND").get("items").get(0);
        assertThat(aliceRow.get("reversed").asBoolean()).isFalse();
        assertThat(activitiesOf(bob.accessToken()).get("totalElements").asLong()).isZero();
    }
}
