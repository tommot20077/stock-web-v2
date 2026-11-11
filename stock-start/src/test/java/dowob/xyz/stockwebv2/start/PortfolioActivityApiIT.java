package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dowob.xyz.stockwebv2.start.support.DecimalStringMatchers.decimalString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
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
            .andExpect(jsonPath("$.data.items[0].quantity", decimalString("10.0")))
            .andExpect(jsonPath("$.data.items[0].amount").value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].ratioNew", equalTo(2)))
            .andExpect(jsonPath("$.data.items[0].ratioOld", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].effectiveDate", equalTo("2026-10-30")))
            .andExpect(jsonPath("$.data.items[0].reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.items[0].reversible", equalTo(true)))
            /* BUY：amount = quantity × price；交易恆不可沖銷。 */
            .andExpect(jsonPath("$.data.items[1].amount", decimalString("2000.0")))
            .andExpect(jsonPath("$.data.items[1].price", decimalString("200.0")))
            .andExpect(jsonPath("$.data.items[1].effectiveDate").value(nullValue()))
            .andExpect(jsonPath("$.data.items[1].reversible", equalTo(false)))
            /* CASH_DIVIDEND：沒有數量；實領總額與預扣稅。 */
            .andExpect(jsonPath("$.data.items[2].quantity").value(nullValue()))
            .andExpect(jsonPath("$.data.items[2].amount", decimalString("120.0")))
            .andExpect(jsonPath("$.data.items[2].withholdingTax", decimalString("30.0")))
            .andExpect(jsonPath("$.data.items[2].reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.items[2].reversible", equalTo(true)))
            .andExpect(jsonPath("$.data.items[3].amount", decimalString("1500.0")))
            .andExpect(jsonPath("$.data.items[0].idempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("篩選：type=DIVIDEND 只回股利、SHARE_ADJUSTMENT 只回調整、BUY 只回買入、symbol=AAPL 只回 AAPL 三種事件、日期為半開區間")
    void filtersSelectKindsSymbolAndHalfOpenRange() throws Exception {
        String accessToken = seedMixedHistory("act-filter");

        activities(accessToken, "type=DIVIDEND")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items[*].kind", contains("CASH_DIVIDEND")));
        activities(accessToken, "type=SHARE_ADJUSTMENT")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items[*].kind", contains("STOCK_DIVIDEND", "SPLIT")));
        activities(accessToken, "type=buy")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items[*].kind", contains("BUY", "BUY")))
            .andExpect(jsonPath("$.data.items[*].symbol", contains("TSLA", "AAPL")));
        activities(accessToken, "type=SELL")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(0)));
        activities(accessToken, "symbol=AAPL")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.items[*].kind", contains("STOCK_DIVIDEND", "CASH_DIVIDEND", "BUY")));
        /* 上界不含：拆股的生效瞬間恰為 2026-10-30T04:00:00Z，不在 [10/29 00:00, 10/30 04:00) 內。 */
        activities(accessToken, "dateFrom=2026-10-29T00:00:00Z&dateTo=2026-10-30T04:00:00Z")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items[*].kind", contains("BUY", "CASH_DIVIDEND")));
        /* 下界含：同一瞬間作為下界時，拆股出現。 */
        activities(accessToken, "dateFrom=2026-10-30T04:00:00Z&dateTo=2026-10-31T00:00:00Z")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].kind", contains("SPLIT")));
    }

    @Test
    @DisplayName("非法參數走統一錯誤信封：type=FOO → 400 fields.type、sort=price → 400 fields.sort、size=abc → 400 fields.size；symbol 查無 → 404 ASSET_NOT_FOUND")
    void invalidParametersReturnFieldErrors() throws Exception {
        String accessToken = register("act-invalid");

        activities(accessToken, "type=FOO")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.type", notNullValue()));
        activities(accessToken, "sort=price")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.sort", notNullValue()));
        activities(accessToken, "size=abc")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.size", notNullValue()));
        activities(accessToken, "symbol=NOPE")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("ASSET_NOT_FOUND")));
    }

    @Test
    @DisplayName("sort=quantity：股利（quantity null）不論方向都排最後；size=1 逐頁走完，id 無重複、數量 = total、順序與單頁一致")
    void nullsLastAndStablePagination() throws Exception {
        String accessToken = seedMixedHistory("act-sort");

        activities(accessToken, "sort=quantity&direction=desc")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[4].kind", equalTo("CASH_DIVIDEND")))
            .andExpect(jsonPath("$.data.items[3].kind", equalTo("STOCK_DIVIDEND")));
        activities(accessToken, "sort=quantity&direction=asc")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].kind", equalTo("STOCK_DIVIDEND")))
            .andExpect(jsonPath("$.data.items[4].kind", equalTo("CASH_DIVIDEND")));
        activities(accessToken, "sort=total&direction=desc")
            .andExpect(status().isOk())
            /* 金額 desc：2000、1500、120，兩筆沒有金額的調整排最後（彼此依 createdAt desc：配股後記）。 */
            .andExpect(jsonPath("$.data.items[*].kind",
                contains("BUY", "BUY", "CASH_DIVIDEND", "STOCK_DIVIDEND", "SPLIT")))
            .andExpect(jsonPath("$.data.items[0].amount", decimalString("2000.0")))
            .andExpect(jsonPath("$.data.items[2].kind", equalTo("CASH_DIVIDEND")))
            .andExpect(jsonPath("$.data.items[3].amount").value(nullValue()))
            .andExpect(jsonPath("$.data.items[4].amount").value(nullValue()));

        /* 三列 quantity 同為 10（兩筆買入與拆股 ΔQ），靠 createdAt、id tie-breaker 才有唯一順序。 */
        List<String> singlePage = ids(data(accessToken, "sort=quantity&direction=desc&size=20"));
        long total = data(accessToken, "sort=quantity&direction=desc&size=1").get("totalElements").asLong();
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < total; page++) {
            walked.addAll(ids(data(accessToken, "sort=quantity&direction=desc&size=1&page=" + page)));
        }
        assertThat(total).isEqualTo(5);
        assertThat(walked).hasSize((int) total).doesNotHaveDuplicates().containsExactlyElementsOf(singlePage);
    }

    @Test
    @DisplayName("沖銷標記：股利沖銷後原列 reversed / 不可沖銷、沖銷列指回原列；兩筆配股只有較晚者可沖銷，沖銷較晚者後較早者變為可沖銷")
    void reversalFlagsAndReversibility() throws Exception {
        String accessToken = register("act-rev");
        buy(accessToken, "AAPL", 10, 150, "2026-10-28T15:00:00Z");
        String dividendId = idOf(postDividend(accessToken, """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"paidAt":"2026-10-29T01:00:00Z"}
            """).andExpect(status().isOk()));
        String dividendReversalId = idOf(postDividend(accessToken, """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"reversalOfId":"%s"}
            """.formatted(dividendId)).andExpect(status().isOk()));
        String earlierId = idOf(postAdjustment(accessToken, """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":1,"effectiveDate":"2026-10-30"}
            """).andExpect(status().isOk()));
        String laterId = idOf(postAdjustment(accessToken, """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":2,"effectiveDate":"2026-10-31"}
            """).andExpect(status().isOk()));

        JsonNode before = data(accessToken, "");
        JsonNode original = itemById(before, dividendId);
        JsonNode reversal = itemById(before, dividendReversalId);
        assertThat(original.get("reversed").asBoolean()).isTrue();
        assertThat(original.get("reversible").asBoolean()).isFalse();
        assertThat(original.get("reversalOfId").isNull()).isTrue();
        assertThat(reversal.get("reversalOfId").asText()).isEqualTo(dividendId);
        assertThat(OffsetDateTime.parse(reversal.get("reversalOfOccurredAt").asText()).toInstant())
            .isEqualTo(OffsetDateTime.parse(original.get("occurredAt").asText()).toInstant());
        assertThat(reversal.get("reversalOfEffectiveDate").isNull()).isTrue();
        /* 沖銷列輸出原值、不反號。 */
        assertThat(reversal.get("amount").asDecimal()).isEqualByComparingTo("120");
        assertThat(reversal.get("reversed").asBoolean()).isFalse();
        assertThat(reversal.get("reversible").asBoolean()).isFalse();
        assertThat(itemById(before, earlierId).get("reversible").asBoolean()).isFalse();
        assertThat(itemById(before, laterId).get("reversible").asBoolean()).isTrue();

        String adjustmentReversalId = idOf(postAdjustment(accessToken, """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":2,"effectiveDate":"2026-10-31","reversalOfId":"%s"}
            """.formatted(laterId)).andExpect(status().isOk()));

        JsonNode after = data(accessToken, "type=SHARE_ADJUSTMENT");
        assertThat(after.get("totalElements").asLong()).isEqualTo(3);
        JsonNode later = itemById(after, laterId);
        JsonNode adjustmentReversal = itemById(after, adjustmentReversalId);
        assertThat(later.get("reversed").asBoolean()).isTrue();
        assertThat(later.get("reversible").asBoolean()).isFalse();
        assertThat(adjustmentReversal.get("reversalOfId").asText()).isEqualTo(laterId);
        assertThat(adjustmentReversal.get("reversalOfEffectiveDate").asText()).isEqualTo("2026-10-31");
        assertThat(adjustmentReversal.get("quantity").asDecimal()).isEqualByComparingTo("2");
        assertThat(adjustmentReversal.get("reversible").asBoolean()).isFalse();
        assertThat(itemById(after, earlierId).get("reversible").asBoolean()).isTrue();
    }

    /**
     * 種一段混合歷史（皆為過去的固定瞬間）：買 AAPL 10 @150（10/28）、AAPL 股利 120（10/29 01:00）、
     * 買 TSLA 10 @200（10/29 15:00）、TSLA 拆股 2:1（除權 10/30，ΔQ +10）、AAPL 配股 1 股（除權 10/31）。
     *
     * @param prefix 使用者前綴
     * @return 該使用者的 access token
     */
    private String seedMixedHistory(String prefix) throws Exception {
        String accessToken = register(prefix);
        buy(accessToken, "AAPL", 10, 150, "2026-10-28T15:00:00Z");
        buy(accessToken, "TSLA", 10, 200, "2026-10-29T15:00:00Z");
        postDividend(accessToken, """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"paidAt":"2026-10-29T01:00:00Z"}
            """).andExpect(status().isOk());
        postAdjustment(accessToken, """
            {"symbol":"TSLA","type":"SPLIT","ratioNew":2,"ratioOld":1,"effectiveDate":"2026-10-30"}
            """).andExpect(status().isOk());
        postAdjustment(accessToken, """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":1,"effectiveDate":"2026-10-31"}
            """).andExpect(status().isOk());
        return accessToken;
    }

    private JsonNode data(String accessToken, String query) throws Exception {
        String body = activities(accessToken, query)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private static List<String> ids(JsonNode data) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : data.get("items")) {
            ids.add(item.get("id").asText());
        }
        return ids;
    }

    private static JsonNode itemById(JsonNode data, String id) {
        for (JsonNode item : data.get("items")) {
            if (item.get("id").asText().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("activity not found in page: " + id);
    }

    private String idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
            .get("data").get("id").asText();
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
