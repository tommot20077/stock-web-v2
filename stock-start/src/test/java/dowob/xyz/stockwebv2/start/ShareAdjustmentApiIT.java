package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/v1/portfolio/share-adjustments} 的端到端整合測試（ADJ-01 tracer）。
 *
 * <p>鎖四條主線：配股改寫持倉（股數增加、總成本不變、均價攤薄，D-05）且 {@code share_adjustments} 恰一列；
 * 拆股依<strong>除權日起點持股</strong>換算，除權日之後的買入不參與（D-08，Pitfall 1）；
 * 無持倉記配股回 409 {@code TRADE_INSUFFICIENT_HOLDING}（D-06）；cookie 登入未帶 CSRF header 被擋。
 * 冪等 payload 比對、沖銷、併發恰一列由 04.2-08 的 IT 補上。</p>
 *
 * <p>AAPL 屬美股：除權日以紐約日曆日表示，生效瞬間為紐約午夜；買入的 {@code executedAt} 一律明確送出三天前，
 * 確保落在「今天 / 昨天」除權日起點之前。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整 API")
@AutoConfigureMockMvc
class ShareAdjustmentApiIT extends ContainerIT {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("持有 1000 股 @100 記配股 50 股：200、持倉 1050 股 @95.23809524，share_adjustments 恰一列")
    void stockDividendDilutesAverageCost() throws Exception {
        String email = "adj-div-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjdiv" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 1000, 100, threeDaysAgo());
        String today = LocalDate.now(NEW_YORK).toString();

        postAdjustment(accessToken, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s","note":"stock dividend"}
            """.formatted(today))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", hasLength(36)))
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data.currency", equalTo("USD")))
            .andExpect(jsonPath("$.data.type", equalTo("STOCK_DIVIDEND")))
            .andExpect(jsonPath("$.data.quantityDelta", equalTo(50.00000000)))
            .andExpect(jsonPath("$.data.ratioNew").value(nullValue()))
            .andExpect(jsonPath("$.data.ratioOld").value(nullValue()))
            .andExpect(jsonPath("$.data.effectiveDate", equalTo(today)))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(1050.00000000)))
            .andExpect(jsonPath("$.data[0].avgCost", equalTo(95.23809524)));

        assertThat(adjustmentRows(email)).isEqualTo(1);
        assertThat(storedQuantityDelta(email)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("除權日前 100 股、除權日後再買 50 股，記拆股 4:1（除權日 = 昨天）：ΔQ = +300，持倉 450 股")
    void splitIsComputedFromQuantityAsOfEffectiveDate() throws Exception {
        String email = "adj-split-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjsplit" + UUID.randomUUID().toString().substring(0, 8));
        buy(accessToken, 100, 400, threeDaysAgo());
        buy(accessToken, 50, 100, null);
        String yesterday = LocalDate.now(NEW_YORK).minusDays(1).toString();

        postAdjustment(accessToken, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"%s"}
            """.formatted(yesterday))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("SPLIT")))
            .andExpect(jsonPath("$.data.ratioNew", equalTo(4)))
            .andExpect(jsonPath("$.data.ratioOld", equalTo(1)))
            .andExpect(jsonPath("$.data.quantityDelta", equalTo(300.00000000)));

        mockMvc.perform(get("/api/v1/portfolio/holdings")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].totalQuantity", equalTo(450.00000000)));
    }

    @Test
    @DisplayName("沒有任何持倉記配股：409 TRADE_INSUFFICIENT_HOLDING，share_adjustments 無列")
    void stockDividendWithoutHoldingIsRejected() throws Exception {
        String email = "adj-none-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "adjnone" + UUID.randomUUID().toString().substring(0, 8));

        postAdjustment(accessToken, UUID.randomUUID().toString(), """
            {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s"}
            """.formatted(LocalDate.now(NEW_YORK)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("TRADE_INSUFFICIENT_HOLDING")));

        assertThat(adjustmentRows(email)).isZero();
    }

    @Test
    @DisplayName("cookie 登入但未帶 CSRF header 的 POST：403，share_adjustments 無新增（negative test）")
    void cookieAuthenticatedPostWithoutCsrfIsForbidden() throws Exception {
        String email = "adj-csrf-" + UUID.randomUUID() + "@example.com";
        register(email, "adjcsrf" + UUID.randomUUID().toString().substring(0, 8));
        Cookie accessCookie = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("stock_access");

        mockMvc.perform(post("/api/v1/portfolio/share-adjustments")
                .cookie(accessCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"STOCK_DIVIDEND","quantity":50,"effectiveDate":"%s"}
                    """.formatted(LocalDate.now(NEW_YORK))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        assertThat(adjustmentRows(email)).isZero();
    }

    private static String threeDaysAgo() {
        return Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * 以 bearer token 買入 AAPL。
     *
     * @param accessToken access token
     * @param quantity    股數
     * @param price       成交價
     * @param executedAt  成交時間（ISO-8601）；null 代表伺服器現在
     */
    private void buy(String accessToken, int quantity, int price, String executedAt) throws Exception {
        String executedAtField = executedAt == null ? "" : ",\"executedAt\":\"" + executedAt + "\"";
        mockMvc.perform(post("/api/v1/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"symbol":"AAPL","type":"BUY","quantity":%d,"price":%d,"fee":0%s}
                    """.formatted(quantity, price, executedAtField)))
            .andExpect(status().isOk());
    }

    private ResultActions postAdjustment(String accessToken, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/share-adjustments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + accessToken)
            .header("Idempotency-Key", idempotencyKey)
            .content(body));
    }

    private int adjustmentRows(String email) {
        Integer rows = jdbcClient.sql("""
                SELECT count(*) FROM share_adjustments
                WHERE user_id = (SELECT id FROM users WHERE email = :email)
                """)
            .param("email", email)
            .query(Integer.class)
            .single();
        return rows;
    }

    private BigDecimal storedQuantityDelta(String email) {
        return jdbcClient.sql("""
                SELECT quantity_delta FROM share_adjustments
                WHERE user_id = (SELECT id FROM users WHERE email = :email)
                """)
            .param("email", email)
            .query(BigDecimal.class)
            .single();
    }

    /**
     * 註冊並登入，回傳 bearer access token。
     *
     * @param email    使用者 email
     * @param username 使用者名稱
     * @return access token
     */
    private String register(String email, String username) throws Exception {
        String password = "Password1";
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
        return data.get("accessToken").asText();
    }
}
