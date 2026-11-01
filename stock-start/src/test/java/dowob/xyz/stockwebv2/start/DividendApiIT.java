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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/v1/portfolio/dividends} 的端到端整合測試（DIV-01 tracer）。
 *
 * <p>鎖三條主線：沒有任何持倉的新使用者也能記一筆現金股利，回 DTO（幣別取自標的、不含冪等鍵）且
 * {@code cash_dividends} 恰一列、{@code holdings} 不受影響（D-01 / D-03）；同 key 同 body 重送回同一筆、
 * 列數不變（D-13 冪等）；cookie 登入未帶 CSRF header 被擋（T-04.2-08）。
 * payload 比對 409、沖銷、欄位驗證由 04.2-04 的 IT 補上。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金股利 API")
@AutoConfigureMockMvc
class DividendApiIT extends ContainerIT {

    private static final String AAPL_DIVIDEND = """
        {"symbol":"AAPL","amount":120,"withholdingTax":30,"paidAt":"2026-10-30T09:15:00+08:00"}
        """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("無任何交易的新使用者記一筆 AAPL 股利：200、幣別 USD、不含冪等鍵；cash_dividends 恰一列、holdings 無列")
    void dividendIsRecordedWithoutAnyHolding() throws Exception {
        String email = "div-api-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "divapi" + UUID.randomUUID().toString().substring(0, 8));

        postDividend(accessToken, UUID.randomUUID().toString(), AAPL_DIVIDEND)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", hasLength(36)))
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")))
            .andExpect(jsonPath("$.data.currency", equalTo("USD")))
            .andExpect(jsonPath("$.data.amount", equalTo(120.00000000)))
            .andExpect(jsonPath("$.data.withholdingTax", equalTo(30.00000000)))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());

        assertThat(dividendRows(email)).isEqualTo(1);
        assertThat(holdingRows(email)).isZero();
    }

    @Test
    @DisplayName("同 key 同 body 重送：200 同一個 id，cash_dividends 仍只有一列")
    void sameKeySameBodyReturnsExistingDividend() throws Exception {
        String email = "div-idem-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "dividem" + UUID.randomUUID().toString().substring(0, 8));
        String key = "div-" + UUID.randomUUID();

        String firstId = dividendIdOf(postDividend(accessToken, key, AAPL_DIVIDEND).andExpect(status().isOk()));
        String secondId = dividendIdOf(postDividend(accessToken, key, AAPL_DIVIDEND).andExpect(status().isOk()));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(dividendRows(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("cookie 登入但未帶 CSRF header 的 POST：403，cash_dividends 無新增（negative test）")
    void cookieAuthenticatedPostWithoutCsrfIsForbidden() throws Exception {
        String email = "div-csrf-" + UUID.randomUUID() + "@example.com";
        register(email, "divcsrf" + UUID.randomUUID().toString().substring(0, 8));
        Cookie accessCookie = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("stock_access");

        mockMvc.perform(post("/api/v1/portfolio/dividends")
                .cookie(accessCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(AAPL_DIVIDEND))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        assertThat(dividendRows(email)).isZero();
    }

    private String dividendIdOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asText();
    }

    private int dividendRows(String email) {
        return countForUser("SELECT count(*) FROM cash_dividends WHERE user_id = (SELECT id FROM users WHERE email = :email)",
            email);
    }

    private int holdingRows(String email) {
        return countForUser("SELECT count(*) FROM holdings WHERE user_id = (SELECT id FROM users WHERE email = :email)",
            email);
    }

    private int countForUser(String sql, String email) {
        Integer rows = jdbcClient.sql(sql)
            .param("email", email)
            .query(Integer.class)
            .single();
        return rows;
    }

    private ResultActions postDividend(String accessToken, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/dividends")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + accessToken)
            .header("Idempotency-Key", idempotencyKey)
            .content(body));
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
