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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /api/v1/portfolio/activities} 的端到端整合測試（PORT-13，D-12）。
 *
 * <p>tracer：交易、現金股利、股數調整三張表的事件在同一個分頁列表依時間交錯排列，且只含登入者自己的紀錄
 * （UNION ALL 的每個分支都限定擁有者，T-04.2-22）。</p>
 *
 * <p>所有事件時間皆為過去的固定瞬間：AAPL / TSLA 屬美股，除權日 2026-10-30 的生效瞬間為紐約午夜
 * （夏令時間，= 2026-10-30T04:00:00Z）；TSLA 的買入落在除權日起點之前，拆股才有除權日起點持股。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("統一活動列表 API")
@AutoConfigureMockMvc
class PortfolioActivityApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("買 AAPL（10/28）→ 記股利（10/29）→ 買 TSLA（10/29 晚）→ 拆股（除權 10/30）：無參數 GET 依 occurredAt desc 交錯回 4 列，他人事件不出現")
    void threeKindsAreInterleavedByOccurredAt() throws Exception {
        String accessToken = register("act-mix");
        String otherToken = register("act-other");
        buy(otherToken, "AAPL", 7, 100, "2026-10-29T10:00:00Z");

        buy(accessToken, "AAPL", 10, 150, "2026-10-28T15:00:00Z");
        postDividend(accessToken, """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"paidAt":"2026-10-29T01:00:00Z"}
            """).andExpect(status().isOk());
        buy(accessToken, "TSLA", 10, 200, "2026-10-29T15:00:00Z");
        postAdjustment(accessToken, """
            {"symbol":"TSLA","type":"SPLIT","ratioNew":2,"ratioOld":1,"effectiveDate":"2026-10-30"}
            """).andExpect(status().isOk());

        activities(accessToken, "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(4)))
            .andExpect(jsonPath("$.data.items", hasSize(4)))
            .andExpect(jsonPath("$.data.items[*].kind", contains("SPLIT", "BUY", "CASH_DIVIDEND", "BUY")))
            .andExpect(jsonPath("$.data.items[*].symbol", contains("TSLA", "TSLA", "AAPL", "AAPL")))
            .andExpect(jsonPath("$.data.items[*].currency", everyItem(equalTo("USD"))))
            /* SPLIT：ΔQ = 10 × 2 / 1 − 10 = +10；沒有金額；除權日原樣；未沖銷且為最晚一筆 → 可沖銷。 */
            .andExpect(jsonPath("$.data.items[0].quantity", equalTo(10.0)))
            .andExpect(jsonPath("$.data.items[0].amount").value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].ratioNew", equalTo(2)))
            .andExpect(jsonPath("$.data.items[0].ratioOld", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].effectiveDate", equalTo("2026-10-30")))
            .andExpect(jsonPath("$.data.items[0].reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.items[0].reversible", equalTo(true)))
            /* BUY：amount = quantity × price；交易恆不可沖銷。 */
            .andExpect(jsonPath("$.data.items[1].amount", equalTo(2000.0)))
            .andExpect(jsonPath("$.data.items[1].price", equalTo(200.0)))
            .andExpect(jsonPath("$.data.items[1].effectiveDate").value(nullValue()))
            .andExpect(jsonPath("$.data.items[1].reversible", equalTo(false)))
            /* CASH_DIVIDEND：沒有數量；實領總額與預扣稅。 */
            .andExpect(jsonPath("$.data.items[2].quantity").value(nullValue()))
            .andExpect(jsonPath("$.data.items[2].amount", equalTo(120.0)))
            .andExpect(jsonPath("$.data.items[2].withholdingTax", equalTo(30.0)))
            .andExpect(jsonPath("$.data.items[2].reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.items[2].reversible", equalTo(true)))
            .andExpect(jsonPath("$.data.items[3].amount", equalTo(1500.0)))
            .andExpect(jsonPath("$.data.items[0].idempotencyKey").doesNotExist());
    }

    /**
     * 以 bearer token 查詢活動列表。
     *
     * @param accessToken access token
     * @param query       query string（不含 {@code ?}）；空字串代表不帶參數
     * @return 回應
     */
    private ResultActions activities(String accessToken, String query) throws Exception {
        String path = "/api/v1/portfolio/activities" + (query.isEmpty() ? "" : "?" + query);
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    /**
     * 以 bearer token 買入。
     *
     * @param accessToken access token
     * @param symbol      標的代號
     * @param quantity    股數
     * @param price       成交價
     * @param executedAt  成交時間（ISO-8601 UTC）
     */
    private void buy(String accessToken, String symbol, int quantity, int price, String executedAt) throws Exception {
        trade(accessToken, symbol, "BUY", quantity, price, executedAt);
    }

    /**
     * 以 bearer token 記一筆成交。
     *
     * @param accessToken access token
     * @param symbol      標的代號
     * @param type        BUY / SELL
     * @param quantity    股數
     * @param price       成交價
     * @param executedAt  成交時間（ISO-8601 UTC）
     */
    private void trade(String accessToken, String symbol, String type, int quantity, int price, String executedAt)
        throws Exception {
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"%s","type":"%s","quantity":%d,"price":%d,"fee":0,"executedAt":"%s"}
                    """.formatted(symbol, type, quantity, price, executedAt)))
            .andExpect(status().isOk());
    }

    private ResultActions postDividend(String accessToken, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/dividends")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + accessToken)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content(body));
    }

    private ResultActions postAdjustment(String accessToken, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/share-adjustments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + accessToken)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content(body));
    }

    /**
     * 註冊並登入一位新使用者，回傳 bearer access token。
     *
     * @param prefix email 與使用者名稱的前綴
     * @return access token
     */
    private String register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        String password = "Password1";
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, prefix.replace("-", "") + suffix, password)))
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
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("accessToken").asText();
    }
}
