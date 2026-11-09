package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractPortfolioE2E;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;

/**
 * 股數調整（ADJ-01）的 REST 層 E2E：配股攤薄、拆股 / 合股依除權日起點持股換算、只能沖銷最晚一筆、
 * 同日多筆依記錄順序疊加，以及 {@code POST /trades} 不接受早於最晚未沖銷調整的成交時間。
 *
 * <p>契約見 {@code ai-docs/trading-portfolio-contract.md}〈POST /api/v1/portfolio/share-adjustments〉：
 * {@code 新股數 = 舊股數 + ΔQ}、總成本不變（均價 scale 8、HALF_UP）；拆股 {@code ΔQ = Q_asOf × new / old}（scale 8、DOWN）
 * {@code − Q_asOf}，{@code Q_asOf} 只含 {@code executed_at} 早於除權日起點的交易與不晚於該瞬間的既有調整；
 * 沖銷撤銷已記錄的 ΔQ、不重算比例。兩項裁決在此以端到端驗證：</p>
 * <ul>
 *   <li>04.2-02 option-recommended：{@code POST /trades} 的 {@code executedAt} 早於最晚未沖銷調整 → 400
 *       {@code fields.executedAt}；把調整全部沖銷後，同一筆補登即可成交。</li>
 *   <li>Yuan 2026-11-04「同日依記錄順序疊加」：同一除權日兩筆 2:1 → 100 股變 400 股；
 *       只有最後記錄、仍有效的那筆可沖銷，倒著沖回原股數。</li>
 * </ul>
 *
 * <p>除權日一律以 {@code TradingDay.of(STOCK, "US")} 換算（不寫死時區）；「三天前」的交易必定早於昨日除權日起點，
 * 「現在」的交易必定晚於今日起點。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Share Adjustment E2E")
class ShareAdjustmentE2E extends AbstractPortfolioE2E {

    /**
     * 三天前（截到秒）的 ISO-8601 時間，必定早於昨日除權日起點。
     *
     * @return ISO-8601 字串
     */
    private static String threeDaysAgo() {
        return Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 今日（美股交易日）。
     *
     * @return 交易日
     */
    private static LocalDate today() {
        return usTradingDate(Instant.now());
    }

    @Test
    @DisplayName("買 1000 @100 → 配股 50（今日除權）→ 持倉 1050 @95.23809524 → 他人沖銷 404 → 本人沖銷 → 回到 1000 @100、列表原列已沖銷")
    void stockDividendDilutesAverageCostAndReversalRestoresIt() throws Exception {
        var alice = auth.register("adj-e2e-dividend@example.com", "adje2edividend", PASSWORD);
        var bob = auth.register("adj-e2e-other@example.com", "adje2eother", PASSWORD);
        String token = alice.accessToken();
        recordTrade(token, "AAPL", "BUY", "1000", "100", threeDaysAgo());
        String body = """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s"%s}
            """;

        JsonNode adjustment = recordShareAdjustment(token, body.formatted(today(), ""));
        String adjustmentId = adjustment.get("id").asText();
        assertThat(adjustment.get("type").asText()).isEqualTo("STOCK_DIVIDEND");
        assertThat(adjustment.get("quantityDelta").decimalValue()).isEqualByComparingTo("50");
        assertThat(adjustment.get("effectiveDate").asText()).isEqualTo(today().toString());
        assertThat(adjustment.get("reversed").asBoolean()).isFalse();
        assertThat(adjustment.has("idempotencyKey")).isFalse();

        JsonNode diluted = holdingOf(holdingsOf(token), "AAPL");
        assertThat(diluted.get("totalQuantity").decimalValue()).isEqualByComparingTo("1050");
        assertThat(diluted.get("avgCost").decimalValue()).isEqualByComparingTo("95.23809524");

        String reversalBody = body.formatted(today(), ",\"reversalOfId\":\"" + adjustmentId + "\"");
        String bobResponse = postShareAdjustment(bob.accessToken(), UUID.randomUUID().toString(), reversalBody)
            .andExpect(status().isNotFound())
            .andExpect(apiError("SHARE_ADJUSTMENT_NOT_FOUND"))
            .andReturn().getResponse().getContentAsString();
        assertThat(bobResponse).doesNotContain(adjustmentId);
        assertThat(activitiesOf(bob.accessToken()).get("totalElements").asLong()).isZero();

        String reversalId = dataOf(postShareAdjustment(token, UUID.randomUUID().toString(), reversalBody)
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(adjustmentId)))).get("id").asText();

        JsonNode restored = holdingOf(holdingsOf(token), "AAPL");
        assertThat(restored.get("totalQuantity").decimalValue()).isEqualByComparingTo("1000");
        assertThat(restored.get("avgCost").decimalValue()).isEqualByComparingTo("100");

        JsonNode listed = activitiesOf(token, "type", "SHARE_ADJUSTMENT");
        assertThat(listed.get("totalElements").asLong()).isEqualTo(2);
        JsonNode original = entryById(listed.get("items"), adjustmentId);
        JsonNode reversal = entryById(listed.get("items"), reversalId);
        assertThat(original.get("reversed").asBoolean()).isTrue();
        assertThat(original.get("reversible").asBoolean()).isFalse();
        assertThat(reversal.get("reversalOfId").asText()).isEqualTo(adjustmentId);
        assertThat(reversal.get("reversalOfEffectiveDate").asText()).isEqualTo(today().toString());
        assertThat(reversal.get("reversible").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("TSLA 三天前 100 + 今天 50 → 拆股 4:1（昨天）450 股 → 合股 1:3（今天）零股 183.33333333 → 沖銷較早的拆股 409、補登三天前交易 400 fields.executedAt → 倒著沖銷回 150 股 → 補登即可成交")
    void splitAndConsolidationUseQuantityAsOfAndOnlyTheLatestIsReversible() throws Exception {
        var session = auth.register("adj-e2e-split@example.com", "adje2esplit", PASSWORD);
        String token = session.accessToken();
        LocalDate yesterday = today().minusDays(1);
        recordTrade(token, "TSLA", "BUY", "100", "400", threeDaysAgo());
        recordTrade(token, "TSLA", "BUY", "50", "100");
        String splitBody = """
            {"symbol":"TSLA","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"%s"%s}
            """;
        String consolidationBody = """
            {"symbol":"TSLA","type":"SPLIT","ratioNew":1,"ratioOld":3,"effectiveDate":"%s"%s}
            """;

        /* 除權日起點持股 100（今天買的 50 不參與換算）→ ΔQ = 100 × 4 − 100 = +300；總成本 45000 不變 → 450 @100。 */
        JsonNode split = recordShareAdjustment(token, splitBody.formatted(yesterday, ""));
        String splitId = split.get("id").asText();
        assertThat(split.get("quantityDelta").decimalValue()).isEqualByComparingTo("300");
        assertThat(split.get("ratioNew").asInt()).isEqualTo(4);
        assertThat(split.get("ratioOld").asInt()).isEqualTo(1);
        JsonNode afterSplit = holdingOf(holdingsOf(token), "TSLA");
        assertThat(afterSplit.get("totalQuantity").decimalValue()).isEqualByComparingTo("450");
        assertThat(afterSplit.get("avgCost").decimalValue()).isEqualByComparingTo("100");

        /* 今日起點持股 = 三天前 100 + 昨日拆股 300 = 400（現在買的 50 晚於起點）→ 400 / 3 截到 8 位 = 133.33333333，ΔQ 為負、零股以小數保留。 */
        JsonNode consolidation = recordShareAdjustment(token, consolidationBody.formatted(today(), ""));
        String consolidationId = consolidation.get("id").asText();
        assertThat(consolidation.get("quantityDelta").decimalValue()).isEqualByComparingTo("-266.66666667");
        JsonNode afterConsolidation = holdingOf(holdingsOf(token), "TSLA");
        assertThat(afterConsolidation.get("totalQuantity").decimalValue()).isEqualByComparingTo("183.33333333");
        assertThat(afterConsolidation.get("avgCost").decimalValue()).isEqualByComparingTo("245.45454546");

        JsonNode listed = activitiesOf(token, "type", "SHARE_ADJUSTMENT", "symbol", "TSLA");
        assertThat(entryById(listed.get("items"), splitId).get("reversible").asBoolean()).isFalse();
        assertThat(entryById(listed.get("items"), consolidationId).get("reversible").asBoolean()).isTrue();

        postShareAdjustment(token, UUID.randomUUID().toString(),
            splitBody.formatted(yesterday, ",\"reversalOfId\":\"" + splitId + "\""))
            .andExpect(status().isConflict())
            .andExpect(apiError("SHARE_ADJUSTMENT_NOT_REVERSIBLE"));

        String backdatedTrade = """
            {"symbol":"TSLA","type":"BUY","quantity":10,"price":100,"fee":0,"executedAt":"%s"}
            """.formatted(threeDaysAgo());
        postTrade(token, backdatedTrade)
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields.executedAt", notNullValue()));
        assertThat(holdingOf(holdingsOf(token), "TSLA").get("totalQuantity").decimalValue())
            .isEqualByComparingTo("183.33333333");
        assertThat(activitiesOf(token, "type", "BUY").get("totalElements").asLong()).isEqualTo(2);

        /* 倒著沖：先沖合股（撤銷 −266.66666667 → 450），拆股才變成最晚一筆、可沖（撤銷 +300 → 150）。 */
        postShareAdjustment(token, UUID.randomUUID().toString(),
            consolidationBody.formatted(today(), ",\"reversalOfId\":\"" + consolidationId + "\""))
            .andExpect(status().isOk())
            .andExpect(apiSuccess());
        JsonNode afterFirstReversal = holdingOf(holdingsOf(token), "TSLA");
        assertThat(afterFirstReversal.get("totalQuantity").decimalValue()).isEqualByComparingTo("450");
        assertThat(afterFirstReversal.get("avgCost").decimalValue()).isEqualByComparingTo("100");

        postShareAdjustment(token, UUID.randomUUID().toString(),
            splitBody.formatted(yesterday, ",\"reversalOfId\":\"" + splitId + "\""))
            .andExpect(status().isOk())
            .andExpect(apiSuccess());
        JsonNode afterSecondReversal = holdingOf(holdingsOf(token), "TSLA");
        assertThat(afterSecondReversal.get("totalQuantity").decimalValue()).isEqualByComparingTo("150");
        assertThat(afterSecondReversal.get("avgCost").decimalValue()).isEqualByComparingTo("300");

        /* 已沒有未沖銷的調整：先前被拒的補登現在可以成交（契約：要補登更早的交易，先沖銷較晚的調整）。 */
        postTrade(token, backdatedTrade)
            .andExpect(status().isOk())
            .andExpect(apiSuccess());
        assertThat(holdingOf(holdingsOf(token), "TSLA").get("totalQuantity").decimalValue())
            .isEqualByComparingTo("160");
    }

    @Test
    @DisplayName("同日依記錄順序疊加（Yuan 2026-11-04）：100 股同一除權日兩次 2:1 → ΔQ +100、+200 共 400 股；只有後記錄者可沖，先沖前者 409，倒著沖回 100 股")
    void sameDayAdjustmentsStackInRecordOrderAndReverseBackwards() throws Exception {
        var session = auth.register("adj-e2e-sameday@example.com", "adje2esameday", PASSWORD);
        String token = session.accessToken();
        LocalDate yesterday = today().minusDays(1);
        recordTrade(token, "MSFT", "BUY", "100", "400", threeDaysAgo());
        String body = """
            {"symbol":"MSFT","type":"SPLIT","ratioNew":2,"ratioOld":1,"effectiveDate":"%s"%s}
            """;

        JsonNode first = recordShareAdjustment(token, body.formatted(yesterday, ""));
        JsonNode second = recordShareAdjustment(token, body.formatted(yesterday, ""));
        String firstId = first.get("id").asText();
        String secondId = second.get("id").asText();
        assertThat(first.get("quantityDelta").decimalValue()).isEqualByComparingTo("100");
        /* 後記錄者的 Q_asOf 含同一除權日已先記錄的 +100 → 200 × 2 − 200 = +200。 */
        assertThat(second.get("quantityDelta").decimalValue()).isEqualByComparingTo("200");
        assertThat(holdingOf(holdingsOf(token), "MSFT").get("totalQuantity").decimalValue())
            .isEqualByComparingTo("400");

        JsonNode listed = activitiesOf(token, "type", "SHARE_ADJUSTMENT");
        assertThat(entryById(listed.get("items"), firstId).get("reversible").asBoolean()).isFalse();
        assertThat(entryById(listed.get("items"), secondId).get("reversible").asBoolean()).isTrue();

        postShareAdjustment(token, UUID.randomUUID().toString(),
            body.formatted(yesterday, ",\"reversalOfId\":\"" + firstId + "\""))
            .andExpect(status().isConflict())
            .andExpect(apiError("SHARE_ADJUSTMENT_NOT_REVERSIBLE"));

        postShareAdjustment(token, UUID.randomUUID().toString(),
            body.formatted(yesterday, ",\"reversalOfId\":\"" + secondId + "\""))
            .andExpect(status().isOk());
        assertThat(holdingOf(holdingsOf(token), "MSFT").get("totalQuantity").decimalValue())
            .isEqualByComparingTo("200");
        JsonNode relisted = activitiesOf(token, "type", "SHARE_ADJUSTMENT");
        assertThat(entryById(relisted.get("items"), firstId).get("reversible").asBoolean()).isTrue();

        postShareAdjustment(token, UUID.randomUUID().toString(),
            body.formatted(yesterday, ",\"reversalOfId\":\"" + firstId + "\""))
            .andExpect(status().isOk());
        JsonNode restored = holdingOf(holdingsOf(token), "MSFT");
        assertThat(restored.get("totalQuantity").decimalValue()).isEqualByComparingTo("100");
        assertThat(restored.get("avgCost").decimalValue()).isEqualByComparingTo("400");
    }
}
