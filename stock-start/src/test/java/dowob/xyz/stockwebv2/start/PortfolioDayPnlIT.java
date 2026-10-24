package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 今日價格損益（PORT-11）的端到端驗收。
 *
 * <p>以 SQL 直接寫 {@code market_prices} 種前收與最新價（前收列在 26 小時前，必然早於任何市場的當日起點；
 * 最新列在 1 分鐘前），並先刪 Redis {@code market:latest:{id}}，確保讀到的是本測試寫的值。
 * 本類使用其他 IT 沒用到的 MSFT / META / AMZN / GOOGL，避免與共用容器內的殘留行情互相污染。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("今日價格損益")
class PortfolioDayPnlIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("持倉列帶單檔今日損益：昨買今買、今天首次買入無前收、只有最新價三種情形")
    void holdingsCarryDayPricePnl() throws Exception {
        AuthTokens tokens = register("daypnl-holdings@example.com", "daypnlholdings", "Password1");
        patchBaseCurrency(tokens, "USD");

        trade(tokens, "MSFT", "BUY", "10", "150", yesterday());
        trade(tokens, "MSFT", "BUY", "5", "195", null);
        trade(tokens, "META", "BUY", "2", "500", null);
        trade(tokens, "AMZN", "BUY", "3", "180", yesterday());
        seedPrices("MSFT", new BigDecimal("190"), new BigDecimal("200"));
        seedPrices("META", null, new BigDecimal("510"));
        seedPrices("AMZN", null, new BigDecimal("190"));

        JsonNode holdings = objectMapper.readTree(mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("data");

        // MSFT：15×200 − 10×190 − 5×195
        JsonNode msft = bySymbol(holdings, "MSFT");
        assertThat(msft.get("dayPricePnl").decimalValue()).isEqualByComparingTo("125.00000000");
        assertThat(msft.get("dayPricePnlRatio").decimalValue()).isEqualByComparingTo("0.04347826");
        assertThat(msft.get("previousClose").decimalValue()).isEqualByComparingTo("190.00000000");

        // META：今天首次買入、沒有前收列，照 D-12 今日買入項計算（D-26）
        JsonNode meta = bySymbol(holdings, "META");
        assertThat(meta.get("dayPricePnl").decimalValue()).isEqualByComparingTo("20.00000000");
        assertThat(meta.get("dayPricePnlRatio").decimalValue()).isEqualByComparingTo("0.02000000");
        assertThat(meta.hasNonNull("previousClose")).isFalse();

        // AMZN：昨日即持有卻沒有前收 → 只有計算需要的價格缺失時才回 null
        JsonNode amzn = bySymbol(holdings, "AMZN");
        assertThat(amzn.hasNonNull("dayPricePnl")).isFalse();
        assertThat(amzn.hasNonNull("dayPricePnlRatio")).isFalse();
    }

    /**
     * 於 holdings 陣列中依代號找出一列。
     *
     * @param holdings holdings 陣列
     * @param symbol   標的代號
     * @return 該列
     */
    private JsonNode bySymbol(JsonNode holdings, String symbol) {
        for (JsonNode holding : holdings) {
            if (symbol.equals(holding.get("symbol").asText())) {
                return holding;
            }
        }
        throw new AssertionError("holding not found: " + symbol);
    }

    /**
     * 26 小時前的 ISO-8601 時間，作為「昨日成交」的 {@code executedAt}。
     *
     * @return ISO-8601 字串
     */
    private String yesterday() {
        return OffsetDateTime.now().minusHours(26).toString();
    }

    /**
     * 送出一筆已成交的手動交易。
     *
     * @param tokens     使用者憑證
     * @param symbol     標的代號
     * @param type       BUY / SELL
     * @param quantity   數量
     * @param price      成交價
     * @param executedAt 明確的成交時間；null 表示由後端補現在
     * @throws Exception MockMvc 失敗時
     */
    private void trade(AuthTokens tokens, String symbol, String type, String quantity, String price, String executedAt)
        throws Exception {
        String executed = executedAt == null ? "" : ",\"executedAt\":\"" + executedAt + "\"";
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"symbol\":\"%s\",\"type\":\"%s\",\"quantity\":%s,\"price\":%s,\"fee\":0%s}"
                    .formatted(symbol, type, quantity, price, executed)))
            .andExpect(status().isOk());
    }

    /**
     * 為標的種前收與最新價：前收列在 26 小時前、最新列在 1 分鐘前；先清掉舊行情與 Redis latest 快取。
     *
     * @param symbol        標的代號
     * @param previousClose 前收；null 表示不寫前收列
     * @param latest        最新價
     */
    private void seedPrices(String symbol, BigDecimal previousClose, BigDecimal latest) {
        Long assetId = jdbcClient.sql("select id from assets where symbol = :symbol")
            .param("symbol", symbol)
            .query(Long.class)
            .single();
        redisTemplate.delete("market:latest:" + assetId);
        jdbcClient.sql("delete from market_prices where asset_id = :assetId")
            .param("assetId", assetId)
            .update();
        if (previousClose != null) {
            insertPrice(assetId, previousClose, "26 hours");
        }
        insertPrice(assetId, latest, "1 minute");
    }

    private void insertPrice(Long assetId, BigDecimal price, String ago) {
        jdbcClient.sql("""
                insert into market_prices(asset_id, time, price, volume)
                values (:assetId, now() - cast(:ago as interval), :price, 1000)
                """)
            .param("assetId", assetId)
            .param("ago", ago)
            .param("price", price)
            .update();
    }

    private void patchBaseCurrency(AuthTokens tokens, String baseCurrency) throws Exception {
        mockMvc.perform(patch("/api/v1/me/preferences")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"" + baseCurrency + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo(baseCurrency)));
    }

    private AuthTokens register(String email, String username, String password) throws Exception {
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
        JsonNode data = objectMapper.readTree(body).get("data");
        return new AuthTokens(data.get("accessToken").asText(), data.get("refreshToken").asText());
    }

    private record AuthTokens(String accessToken, String refreshToken) {
    }
}
