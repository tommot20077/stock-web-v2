package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 現金股利進入 summary（DIV-02）的端到端驗收。
 *
 * <p>鎖三件事：記一筆 AAPL 股利（120 USD、預扣 30）後<strong>立即</strong>重讀 summary，USD 現金 +120、
 * {@code dividendIncome} / {@code dividendTaxWithheld} 以 V2 seed 的 USD/TWD 32.418 折成預設基準幣 TWD、
 * {@code totalPnl} 含股利（D-01）；沖銷後重讀回到 0（沖銷列反號聚合，且股利寫入的 {@code afterCommit}
 * 失效生效，不需等 60 秒 TTL）；兩個使用者各記股利，summary 互不包含（T-04.2-12，聚合綁 {@code user_id}）。
 * 每個測試使用獨立的新使用者，互不污染。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("現金股利進入 summary")
class PortfolioDividendSummaryIT extends ContainerIT {

    private static final String AAPL_DIVIDEND = """
        {"symbol":"AAPL","amount":120,"withholdingTax":30}
        """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("無交易的新使用者記 AAPL 股利 120（預扣 30）→ summary 立即反映；沖銷 → dividendIncome 與 USD 現金回到 0")
    void dividendIsReflectedImmediatelyAndReversalNetsToZero() throws Exception {
        String token = register("divsum-" + UUID.randomUUID() + "@example.com");
        /* 先讀一次讓 summary 進快取（TTL 60 秒）；若股利寫入沒有使快取失效，下一次讀會拿到這份舊值。 */
        JsonNode before = summary(token);
        assertThat(before.get("dividendIncome").decimalValue()).isEqualByComparingTo("0");
        assertThat(before.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("0");

        String dividendId = dividendIdOf(postDividend(token, UUID.randomUUID().toString(), AAPL_DIVIDEND)
            .andExpect(status().isOk()));

        JsonNode afterDividend = summary(token);
        assertThat(balanceOf(afterDividend, "USD").get("amount").decimalValue()).isEqualByComparingTo("120");
        /* 120 × 32.418、30 × 32.418 */
        assertThat(afterDividend.get("dividendIncome").decimalValue()).isEqualByComparingTo("3890.16");
        assertThat(afterDividend.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("972.54");
        assertThat(afterDividend.get("cash").decimalValue()).isEqualByComparingTo("3890.16");
        assertThat(afterDividend.get("totalPnl").decimalValue()).isEqualByComparingTo("3890.16");

        postDividend(token, UUID.randomUUID().toString(), """
                {"symbol":"AAPL","amount":120,"withholdingTax":30,"reversalOfId":"%s"}
                """.formatted(dividendId))
            .andExpect(status().isOk());

        JsonNode afterReversal = summary(token);
        assertThat(afterReversal.get("dividendIncome").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterReversal.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("0");
        assertThat(balanceOf(afterReversal, "USD").get("amount").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterReversal.get("cash").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterReversal.get("totalPnl").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("兩個使用者各記股利：summary 只含自己的股利（netByAsset 綁 user_id，T-04.2-12）")
    void dividendsAreAggregatedPerUser() throws Exception {
        String alice = register("divsum-a-" + UUID.randomUUID() + "@example.com");
        String bob = register("divsum-b-" + UUID.randomUUID() + "@example.com");

        postDividend(alice, UUID.randomUUID().toString(), AAPL_DIVIDEND).andExpect(status().isOk());
        postDividend(bob, UUID.randomUUID().toString(), """
                {"symbol":"AAPL","amount":10,"withholdingTax":1}
                """).andExpect(status().isOk());

        JsonNode aliceSummary = summary(alice);
        assertThat(balanceOf(aliceSummary, "USD").get("amount").decimalValue()).isEqualByComparingTo("120");
        assertThat(aliceSummary.get("dividendIncome").decimalValue()).isEqualByComparingTo("3890.16");

        JsonNode bobSummary = summary(bob);
        assertThat(balanceOf(bobSummary, "USD").get("amount").decimalValue()).isEqualByComparingTo("10");
        /* 10 × 32.418、1 × 32.418 */
        assertThat(bobSummary.get("dividendIncome").decimalValue()).isEqualByComparingTo("324.18");
        assertThat(bobSummary.get("dividendTaxWithheld").decimalValue()).isEqualByComparingTo("32.418");
    }

    private JsonNode summary(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/v1/portfolio/summary")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("data");
    }

    private JsonNode balanceOf(JsonNode summary, String currency) {
        for (JsonNode balance : summary.get("cashBalances")) {
            if (currency.equals(balance.get("currency").asText())) {
                return balance;
            }
        }
        throw new AssertionError("cashBalances has no " + currency + ": " + summary.get("cashBalances"));
    }

    private ResultActions postDividend(String token, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/dividends")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .content(body));
    }

    private String dividendIdOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asText();
    }

    private String register(String email) throws Exception {
        String password = "Password1";
        String username = "divsum" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }
}
