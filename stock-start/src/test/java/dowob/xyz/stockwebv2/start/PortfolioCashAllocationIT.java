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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 可用現金（CASH-02）的端到端驗收。
 *
 * <p>現金是「入出金 ± 全部交易現金流」的推導值（D-10，不存餘額），所以只買不入金的帳戶現金為負（D-08）。
 * 本類使用其他 IT 沒用到的 TSLA（USD），基準幣取預設 TWD、匯率取 V2 seed 的 USD/TWD 32.418；
 * 以 SQL 直接寫 {@code market_prices} 種最新價，並先刪 Redis {@code market:latest:{id}}，
 * 確保讀到的是本測試寫的值。每個測試使用獨立的新使用者，互不污染。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("可用現金與資產配置")
class PortfolioCashAllocationIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("只買不入金：USD 現金為 -1000、基準幣現金為負，總資產 = 市值 + 現金 = 0（D-08）")
    void onlyBuyingWithoutDepositMakesCashNegative() throws Exception {
        String token = register("cashalloc-neg-" + UUID.randomUUID() + "@example.com");
        seedLatestPrice("TSLA", new BigDecimal("100"));
        trade(token, "TSLA", "BUY", "10", "100", "0");

        JsonNode summary = summary(token);

        JsonNode usd = balanceOf(summary, "USD");
        assertThat(usd).as("USD 原幣餘額").isNotNull();
        assertThat(usd.get("amount").decimalValue()).isEqualByComparingTo("-1000.00000000");
        // -1000 USD × 32.418（V2 seed）
        assertThat(summary.get("cash").decimalValue()).isEqualByComparingTo("-32418.00000000");
        // 10 股 × 市價 100 = 1000 USD = 32418 TWD，與負現金恰好抵銷
        assertThat(summary.get("totalMarketValue").decimalValue()).isEqualByComparingTo("32418.00000000");
        assertThat(summary.get("totalEquity").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("入金 commit 後立刻重讀 summary 即反映新現金：快取在分錄 commit 之後失效（D-22）")
    void depositRaisesCashImmediatelyAfterCommit() throws Exception {
        String token = register("cashalloc-dep-" + UUID.randomUUID() + "@example.com");
        seedLatestPrice("TSLA", new BigDecimal("100"));
        trade(token, "TSLA", "BUY", "10", "100", "0");
        // 先讀一次，讓 summary 進快取（TTL 60 秒）；若入金沒有使快取失效，下一次讀會拿到這份舊值。
        assertThat(summary(token).get("cash").decimalValue()).isEqualByComparingTo("-32418.00000000");

        postCashEntry(token, "DEPOSIT", "TWD", "50000", null);

        JsonNode summary = summary(token);
        // −1000 USD × 32.418 + 50000 TWD
        assertThat(summary.get("cash").decimalValue()).isEqualByComparingTo("17582.00000000");
        assertThat(summary.get("totalEquity").decimalValue()).isEqualByComparingTo("50000.00000000");
        assertThat(balanceOf(summary, "TWD").get("amount").decimalValue()).isEqualByComparingTo("50000.00000000");
        assertThat(balanceOf(summary, "USD").get("amount").decimalValue()).isEqualByComparingTo("-1000.00000000");
    }

    @Test
    @DisplayName("賣出加入淨收入（數量 × 價格 − 手續費）：USD 餘額由 -1000 變 -561")
    void sellAddsProceedsNetOfFee() throws Exception {
        String token = register("cashalloc-sell-" + UUID.randomUUID() + "@example.com");
        seedLatestPrice("TSLA", new BigDecimal("100"));
        trade(token, "TSLA", "BUY", "10", "100", "0");
        assertThat(balanceOf(summary(token), "USD").get("amount").decimalValue())
            .isEqualByComparingTo("-1000.00000000");

        trade(token, "TSLA", "SELL", "4", "110", "1");

        // −1000 + (4 × 110 − 1)
        assertThat(balanceOf(summary(token), "USD").get("amount").decimalValue())
            .isEqualByComparingTo("-561.00000000");
    }

    @Test
    @DisplayName("出金扣減餘額；沖銷該筆出金後回到原值（沖銷是反向分錄，自然抵銷）")
    void withdrawalAndReversalAreReflected() throws Exception {
        String token = register("cashalloc-wd-" + UUID.randomUUID() + "@example.com");
        postCashEntry(token, "DEPOSIT", "TWD", "50000", null);
        assertThat(balanceOf(summary(token), "TWD").get("amount").decimalValue())
            .isEqualByComparingTo("50000.00000000");

        JsonNode withdrawal = postCashEntry(token, "WITHDRAWAL", "TWD", "10000", null);
        assertThat(balanceOf(summary(token), "TWD").get("amount").decimalValue())
            .isEqualByComparingTo("40000.00000000");

        postCashEntry(token, "DEPOSIT", "TWD", "10000", withdrawal.get("id").asText());

        JsonNode summary = summary(token);
        assertThat(balanceOf(summary, "TWD").get("amount").decimalValue()).isEqualByComparingTo("50000.00000000");
        assertThat(summary.get("cash").decimalValue()).isEqualByComparingTo("50000.00000000");
    }

    /**
     * 記一筆入出金（或沖銷）。
     *
     * @param token        access token
     * @param type         DEPOSIT / WITHDRAWAL
     * @param currency     幣別代碼
     * @param amount       金額
     * @param reversalOfId 沖銷對象的分錄 id；一般入出金為 null
     * @return 回應的 {@code data}（分錄 DTO）
     * @throws Exception MockMvc 失敗時
     */
    private JsonNode postCashEntry(String token, String type, String currency, String amount, String reversalOfId)
        throws Exception {
        String reversal = reversalOfId == null ? "" : ",\"reversalOfId\":\"" + reversalOfId + "\"";
        String body = mockMvc.perform(post("/api/v1/portfolio/cash-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"type\":\"%s\",\"currency\":\"%s\",\"amount\":%s%s}"
                    .formatted(type, currency, amount, reversal)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    /**
     * 讀取 summary 的 data 節點。
     *
     * @param token access token
     * @return {@code $.data}
     * @throws Exception MockMvc 失敗時
     */
    private JsonNode summary(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/v1/portfolio/summary")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("data");
    }

    /**
     * 在 {@code cashBalances} 中依幣別找出一列。
     *
     * @param summary  summary 的 data 節點
     * @param currency 幣別代碼
     * @return 該列；沒有該幣別時為 null
     */
    private JsonNode balanceOf(JsonNode summary, String currency) {
        for (JsonNode balance : summary.get("cashBalances")) {
            if (currency.equals(balance.get("currency").asText())) {
                return balance;
            }
        }
        return null;
    }

    /**
     * 送出一筆已成交的手動交易（發生時間由後端補現在）。
     *
     * @param token    access token
     * @param symbol   標的代號
     * @param type     BUY / SELL
     * @param quantity 數量
     * @param price    成交價
     * @param fee      手續費
     * @throws Exception MockMvc 失敗時
     */
    private void trade(String token, String symbol, String type, String quantity, String price, String fee)
        throws Exception {
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"symbol\":\"%s\",\"type\":\"%s\",\"quantity\":%s,\"price\":%s,\"fee\":%s}"
                    .formatted(symbol, type, quantity, price, fee)))
            .andExpect(status().isOk());
    }

    /**
     * 為標的種最新價（1 分鐘前的單一列）；先清掉舊行情與 Redis latest 快取。
     *
     * @param symbol 標的代號
     * @param latest 最新價
     */
    private void seedLatestPrice(String symbol, BigDecimal latest) {
        Long assetId = jdbcClient.sql("select id from assets where symbol = :symbol")
            .param("symbol", symbol)
            .query(Long.class)
            .single();
        redisTemplate.delete("market:latest:" + assetId);
        jdbcClient.sql("delete from market_prices where asset_id = :assetId")
            .param("assetId", assetId)
            .update();
        jdbcClient.sql("""
                insert into market_prices(asset_id, time, price, volume)
                values (:assetId, now() - interval '1 minute', :price, 1000)
                """)
            .param("assetId", assetId)
            .param("price", latest)
            .update();
    }

    /**
     * 註冊並登入一個新使用者。
     *
     * @param email 使用者 email（前綴 {@code cashalloc-}）
     * @return access token
     * @throws Exception MockMvc 失敗時
     */
    private String register(String email) throws Exception {
        String username = "ca" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"Password1"}
                    """.formatted(email, username)))
            .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }
}
