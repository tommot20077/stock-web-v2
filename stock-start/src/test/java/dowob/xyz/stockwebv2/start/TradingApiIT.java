package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TradingApiIT extends ContainerIT {

    /**
     * 三筆固定交易的成交時間；刻意與插入順序（即 created_at 順序）不一致，
     * 用來證明排序與日期篩選是以 executed_at 而非 created_at 為準。
     */
    private static final String EXECUTED_AT_OLDEST = "2026-01-10T00:00:00Z";
    private static final String EXECUTED_AT_MIDDLE = "2026-02-10T00:00:00Z";
    private static final String EXECUTED_AT_NEWEST = "2026-03-10T00:00:00Z";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void tradingEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buyBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }

    @Test
    void buyThenSellUpdatesHoldingsAndPortfolioSummary() throws Exception {
        AuthTokens tokens = register("trading-owner@example.com", "tradingowner", "Password1");

        String buyResponse = mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(buyBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", notNullValue()))
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data.type", equalTo("BUY")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String buyId = objectMapper.readTree(buyResponse).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/trades?page=0&size=20&symbol=AAPL")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id", equalTo(buyId)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(10.00000000)))
            .andExpect(jsonPath("$.data[0].avgCost", equalTo(100.50000000)))
            .andExpect(jsonPath("$.data[0].marketPrice", equalTo(218.40000000)))
            .andExpect(jsonPath("$.data[0].unrealizedPnl", equalTo(1179.00000000)));

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("""
                    {"symbol":"AAPL","type":"SELL","quantity":4,"price":120,"fee":1,"note":"partial exit"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("SELL")));

        mockMvc.perform(get("/api/v1/portfolio/summary")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.holdingCount", equalTo(1)))
            .andExpect(jsonPath("$.data.totalCostBasis", equalTo(603.00000000)))
            .andExpect(jsonPath("$.data.totalMarketValue", equalTo(1310.40000000)))
            .andExpect(jsonPath("$.data.realizedPnl", equalTo(77.00000000)))
            .andExpect(jsonPath("$.data.unrealizedPnl", equalTo(707.40000000)));
    }

    @Test
    void fullyClosedPositionStillCountsRealizedPnlInSummary() throws Exception {
        AuthTokens tokens = register("trading-fullclose@example.com", "tradingfullclose", "Password1");

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(buyBody()))
            .andExpect(status().isOk());

        // 全數賣出 → 部位平倉（total_quantity = 0）；realized_pnl 仍留在該 holdings row
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("""
                    {"symbol":"AAPL","type":"SELL","quantity":10,"price":120,"fee":1,"note":"full exit"}
                    """))
            .andExpect(status().isOk());

        // 已實現損益 (120 - 100.5) * 10 - 1 = 194，即使部位已平倉、不再出現在 holdings 清單，
        // summary 的 realizedPnl / totalPnl 仍應計入（否則平倉即遺失已實現損益）。
        mockMvc.perform(get("/api/v1/portfolio/summary")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.holdingCount", equalTo(0)))
            .andExpect(jsonPath("$.data.realizedPnl", equalTo(194.00000000)))
            .andExpect(jsonPath("$.data.totalPnl", equalTo(194.00000000)));
    }

    @Test
    void sellRejectsOversell() throws Exception {
        AuthTokens tokens = register("trading-oversell@example.com", "tradingoversell", "Password1");

        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("""
                    {"symbol":"AAPL","type":"SELL","quantity":1,"price":120,"fee":0}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_INSUFFICIENT_HOLDING")));
    }

    @Test
    void concurrentFirstBuysMergeWithoutUniqueViolation() throws Exception {
        AuthTokens tokens = register("trading-concurrent@example.com", "tradingconcurrent", "Password1");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post("/api/v1/trades")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokens.accessToken())
                            .content("""
                                {"symbol":"AAPL","type":"BUY","quantity":1,"price":100,"fee":0}
                                """))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            // 同一 user+asset 幾乎同時的首次 BUY：findHoldingForUpdate 對不存在的 row 不加鎖，
            // 若無 upsert/重試，多筆都會走 insert，(user_id, asset_id) 唯一鍵讓後續變 500。
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        // 8 筆首次 BUY 應全部併入同一持倉（總量 8），而非丟失或報錯。
        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(8.00000000)));
    }

    @Test
    @DisplayName("預設排序以 executed_at 降冪，而非 created_at")
    void defaultSortUsesExecutedAtInsteadOfCreatedAt() throws Exception {
        AuthTokens tokens = register("trading-defaultsort@example.com", "tradingdefaultsort", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * 第三筆（backfill）的 created_at 最新但 executed_at 最舊；若排序仍走 created_at，
         * 它會排在最前面。此斷言即是預設排序已切換到 executed_at 的直接證據。
         */
        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.sellId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[2].id", equalTo(fixture.backfillBuyId())));
    }

    @Test
    @DisplayName("type 篩選同時作用於 items 與 totalElements，並可與 symbol 組合")
    void typeFilterAppliesToItemsAndTotalElements() throws Exception {
        AuthTokens tokens = register("trading-typefilter@example.com", "tradingtypefilter", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * count 與 list 若使用各自的 WHERE 字串，totalElements 會停留在 3 而 items 只有 2 —
         * 此斷言鎖定兩者共用同一份篩選條件。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("type", "BUY")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.backfillBuyId())));

        mockMvc.perform(get("/api/v1/trades")
                .param("symbol", "AAPL")
                .param("type", "buy")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)));
    }

    @Test
    @DisplayName("日期區間為半開區間：含 dateFrom、不含 dateTo")
    void dateRangeFilterIsHalfOpen() throws Exception {
        AuthTokens tokens = register("trading-daterange@example.com", "tradingdaterange", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", EXECUTED_AT_MIDDLE)
                .param("dateTo", EXECUTED_AT_NEWEST)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.buyId())));
    }

    @Test
    @DisplayName("sort=total 降冪，金額平手時以 id 降冪決定順序")
    void sortByTotalBreaksTiesDeterministicallyById() throws Exception {
        AuthTokens tokens = register("trading-sorttotal@example.com", "tradingsorttotal", "Password1");
        TradeFixture fixture = seedThreeTrades(tokens);

        /*
         * 三筆金額為 1000（10 × 100）、480（4 × 120）、1000（1 × 1000）；兩筆 1000 平手，
         * 由 id 降冪決定後插入者在前。缺少 tie-breaker 時順序不確定，翻頁會重複或遺漏。
         */
        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "total")
                .param("direction", "desc")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id", equalTo(fixture.backfillBuyId())))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(fixture.buyId())))
            .andExpect(jsonPath("$.data.items[2].id", equalTo(fixture.sellId())));
    }

    @Test
    @DisplayName("sort=quantity 升冪回傳 1 / 4 / 10")
    void sortByQuantityAscending() throws Exception {
        AuthTokens tokens = register("trading-sortquantity@example.com", "tradingsortquantity", "Password1");
        seedThreeTrades(tokens);

        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "quantity")
                .param("direction", "asc")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity", equalTo(1.00000000)))
            .andExpect(jsonPath("$.data.items[1].quantity", equalTo(4.00000000)))
            .andExpect(jsonPath("$.data.items[2].quantity", equalTo(10.00000000)));
    }

    @Test
    @DisplayName("白名單外的 sort 與 direction 回 400 VALIDATION_FAILED")
    void invalidSortAndDirectionAreRejected() throws Exception {
        AuthTokens tokens = register("trading-badsort@example.com", "tradingbadsort", "Password1");

        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "evil")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));

        mockMvc.perform(get("/api/v1/trades")
                .param("sort", "executed_at; drop table transactions")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));

        mockMvc.perform(get("/api/v1/trades")
                .param("direction", "up")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("不支援的 type 回 400 TRADE_UNSUPPORTED_TYPE，非法日期回 400 VALIDATION_FAILED")
    void invalidTypeAndDateAreRejected() throws Exception {
        AuthTokens tokens = register("trading-badfilter@example.com", "tradingbadfilter", "Password1");

        mockMvc.perform(get("/api/v1/trades")
                .param("type", "DIV")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_UNSUPPORTED_TYPE")));

        mockMvc.perform(get("/api/v1/trades")
                .param("dateFrom", "not-a-date")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("任何篩選與排序組合都不會洩漏其他使用者的交易")
    void filtersNeverLeakOtherUsersTrades() throws Exception {
        AuthTokens owner = register("trading-isolation-a@example.com", "tradingisolationa", "Password1");
        AuthTokens other = register("trading-isolation-b@example.com", "tradingisolationb", "Password1");
        seedThreeTrades(owner);
        String foreignId = createTrade(other, """
            {"symbol":"AAPL","type":"BUY","quantity":7,"price":999,"fee":0,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_NEWEST));

        /*
         * 另一使用者的交易金額（7 × 999 = 6993）與成交時間都足以在每一種排序下排到第一位，
         * 若 WHERE 漏掉 user_id 條件，下列任一組合都會立刻暴露。
         */
        String[][] combinations = {
            {},
            {"type", "BUY"},
            {"sort", "total", "direction", "desc"},
            {"sort", "quantity", "direction", "desc"},
            {"dateFrom", EXECUTED_AT_OLDEST, "dateTo", "2026-12-31T00:00:00Z"}
        };
        for (String[] combination : combinations) {
            var request = get("/api/v1/trades").header("Authorization", "Bearer " + owner.accessToken());
            for (int i = 0; i < combination.length; i += 2) {
                request = request.param(combination[i], combination[i + 1]);
            }
            String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(body).doesNotContain(foreignId);
        }

        mockMvc.perform(get("/api/v1/trades")
                .header("Authorization", "Bearer " + other.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(foreignId)));
    }

    /**
     * 建立三筆交易，刻意讓 created_at 與 executed_at 的順序不一致：
     * 最後插入的 backfill 交易 created_at 最新、executed_at 最舊。
     *
     * @param tokens 交易擁有者的權杖
     * @return 三筆交易的識別碼
     */
    private TradeFixture seedThreeTrades(AuthTokens tokens) throws Exception {
        String buyId = createTrade(tokens, """
            {"symbol":"AAPL","type":"BUY","quantity":10,"price":100,"fee":5,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_MIDDLE));
        String sellId = createTrade(tokens, """
            {"symbol":"AAPL","type":"SELL","quantity":4,"price":120,"fee":1,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_NEWEST));
        String backfillBuyId = createTrade(tokens, """
            {"symbol":"AAPL","type":"BUY","quantity":1,"price":1000,"fee":0,"executedAt":"%s"}
            """.formatted(EXECUTED_AT_OLDEST));
        return new TradeFixture(buyId, sellId, backfillBuyId);
    }

    private String createTrade(AuthTokens tokens, String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).get("data").get("id").asText();
    }

    private record TradeFixture(String buyId, String sellId, String backfillBuyId) {
    }

    private String buyBody() {
        return """
            {"symbol":"AAPL","type":"BUY","quantity":10,"price":100,"fee":5,"note":"initial buy"}
            """;
    }

    private AuthTokens register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

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
        return new AuthTokens(data.get("accessToken").asText(), data.get("refreshToken").asText());
    }

    private record AuthTokens(String accessToken, String refreshToken) {
    }
}
