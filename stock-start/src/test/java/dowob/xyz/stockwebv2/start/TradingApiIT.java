package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
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
