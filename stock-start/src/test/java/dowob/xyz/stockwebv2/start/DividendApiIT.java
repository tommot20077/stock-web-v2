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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /api/v1/portfolio/dividends} 的端到端整合測試（DIV-01 tracer）。
 *
 * <p>鎖三條主線：沒有任何持倉的新使用者也能記一筆現金股利，回 DTO（幣別取自標的、不含冪等鍵）且
 * {@code cash_dividends} 恰一列、{@code holdings} 不受影響（D-01 / D-03）；同 key 同 body 重送回同一筆、
 * 列數不變（D-13 冪等）；cookie 登入未帶 CSRF header 被擋（T-04.2-08）。
 * 04.2-04 起另鎖沖銷（原列變 reversed、重複沖銷 / 沖銷「沖銷列」409、跨使用者 404、不同 key 併發沖銷只有一筆成功）、
 * 同 key 不同 payload 409、缺 {@code Idempotency-Key} 的 {@code fields}、欄位越界與沖銷值不符的 {@code fields} 歸屬。</p>
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

    @Test
    @DisplayName("記一筆 → 新 key 沖銷：200、reversalOfId 指回原列；原 key 重送看到 reversed = true；第三把 key 再沖 → 409 DIVIDEND_ALREADY_REVERSED；沖銷「沖銷列」→ 409 DIVIDEND_NOT_REVERSIBLE")
    void reversalMarksOriginalAsReversed() throws Exception {
        String email = "div-rev-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "divrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalKey = "div-" + UUID.randomUUID();
        String originalId = dividendIdOf(postDividend(accessToken, originalKey, AAPL_DIVIDEND)
            .andExpect(status().isOk()));

        String reversalId = dividendIdOf(postDividend(accessToken, UUID.randomUUID().toString(),
                reversalBody(originalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(originalId)))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.amount", equalTo(120.00000000)))
            .andExpect(jsonPath("$.data.withholdingTax", equalTo(30.00000000))));

        /* 原 key 同 body 重送走冪等命中，DTO 的 reversed 讀自帳本：此時原列已被沖銷。 */
        postDividend(accessToken, originalKey, AAPL_DIVIDEND)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", equalTo(originalId)))
            .andExpect(jsonPath("$.data.reversed", equalTo(true)));

        postDividend(accessToken, UUID.randomUUID().toString(), reversalBody(originalId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("DIVIDEND_ALREADY_REVERSED")));

        postDividend(accessToken, UUID.randomUUID().toString(), reversalBody(reversalId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("DIVIDEND_NOT_REVERSIBLE")));

        assertThat(dividendRows(email)).isEqualTo(2);
    }

    @Test
    @DisplayName("使用者 B 沖銷 A 的股利：404 DIVIDEND_NOT_FOUND，回應不含 A 的 uuid 與金額；A 的帳本不變、B 無列")
    void otherUsersDividendCannotBeReversed() throws Exception {
        String ownerEmail = "div-owner-" + UUID.randomUUID() + "@example.com";
        String ownerToken = register(ownerEmail, "divowner" + UUID.randomUUID().toString().substring(0, 8));
        String ownerDividend = dividendIdOf(postDividend(ownerToken, UUID.randomUUID().toString(), """
                {"symbol":"AAPL","amount":987.65,"withholdingTax":12.34}
                """)
            .andExpect(status().isOk()));

        String intruderEmail = "div-intruder-" + UUID.randomUUID() + "@example.com";
        String intruderToken = register(intruderEmail, "divintr" + UUID.randomUUID().toString().substring(0, 8));
        String body = postDividend(intruderToken, UUID.randomUUID().toString(), """
                {"symbol":"AAPL","amount":987.65,"withholdingTax":12.34,"reversalOfId":"%s"}
                """.formatted(ownerDividend))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("DIVIDEND_NOT_FOUND")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        // 只檢查回應中「非 meta」的部分:meta.timestamp 是伺服器時間(例如 …:56:12.340947…),
        // 秒數與小數可能湊出 "12.34" 這類字串,raw body 比對會偶發誤判;traceId / timestamp 與資料洩漏無關。
        ObjectNode response = (ObjectNode) objectMapper.readTree(body);
        response.remove("meta");
        String withoutMeta = response.toString();
        assertThat(withoutMeta).doesNotContain(ownerDividend).doesNotContain("987.65").doesNotContain("12.34");

        assertThat(dividendRows(ownerEmail)).isEqualTo(1);
        assertThat(dividendRows(intruderEmail)).isZero();
    }

    @Test
    @DisplayName("8 條併發、各自不同 key 沖銷同一筆股利：恰一條 200，其餘 409 DIVIDEND_ALREADY_REVERSED；cash_dividends 恰兩列")
    void concurrentReversalsWithDifferentKeysCreateExactlyOne() throws Exception {
        String email = "div-race-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "divrace" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = dividendIdOf(postDividend(accessToken, UUID.randomUUID().toString(), AAPL_DIVIDEND)
            .andExpect(status().isOk()));
        String body = reversalBody(originalId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> responses = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                responses.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    var response = postDividend(accessToken, UUID.randomUUID().toString(), body)
                        .andReturn().getResponse();
                    return response.getStatus() + " " + response.getContentAsString();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int succeeded = 0;
            for (Future<String> response : responses) {
                String raw = response.get(30, TimeUnit.SECONDS);
                if (raw.startsWith("200 ")) {
                    succeeded++;
                } else {
                    /* 落敗者不論是在 isReversed 檢查被擋，或 insert 被 uk_cash_dividends_reversal_of 吸收，都必須是同一個 code。 */
                    assertThat(raw).startsWith("409 ");
                    assertThat(objectMapper.readTree(raw.substring(4)).get("error").get("code").asText())
                        .isEqualTo("DIVIDEND_ALREADY_REVERSED");
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(dividendRows(email)).isEqualTo(2);
    }

    @Test
    @DisplayName("同 key 不同 payload：409 DIVIDEND_IDEMPOTENCY_KEY_REUSED、不回射 key、列數不變；只改 note（數值 scale 不同）重送回既有那筆")
    void sameKeyWithDifferentPayloadIsRejectedAsReuse() throws Exception {
        String email = "div-reuse-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "divreuse" + UUID.randomUUID().toString().substring(0, 8));
        String key = "div-reuse-" + UUID.randomUUID();
        String id = dividendIdOf(postDividend(accessToken, key, AAPL_DIVIDEND).andExpect(status().isOk()));

        String error = postDividend(accessToken, key, """
                {"symbol":"AAPL","amount":121,"withholdingTax":30,"paidAt":"2026-10-30T09:15:00+08:00"}
                """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("DIVIDEND_IDEMPOTENCY_KEY_REUSED")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(error).doesNotContain(key);

        postDividend(accessToken, key, """
                {"symbol":"AAPL","amount":120.00000000,"withholdingTax":30.0,
                 "paidAt":"2026-10-30T01:15:00Z","note":"changed"}
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", equalTo(id)));

        assertThat(dividendRows(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key：400 VALIDATION_FAILED、error.fields['Idempotency-Key']，cash_dividends 無列")
    void missingIdempotencyKeyIsRejectedWithFieldError() throws Exception {
        String email = "div-nokey-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "divnokey" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/portfolio/dividends")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(AAPL_DIVIDEND))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']", notNullValue()));

        assertThat(dividendRows(email)).isZero();
    }

    @Test
    @DisplayName("欄位越界與沖銷值不符：400 VALIDATION_FAILED 且 fields 指名欄位（amount / withholdingTax / note / reversalOfId / paidAt 未來與早於 1900-01-01）；回應不回射 symbol 與 key")
    void fieldErrorsNameTheOffendingField() throws Exception {
        String email = "div-fields-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "divfield" + UUID.randomUUID().toString().substring(0, 8));
        String canaryKey = "LEAK-CANARY-" + UUID.randomUUID();

        assertFieldError(accessToken, canaryKey, """
            {"symbol":"CANARYSYM","amount":0}
            """, "amount");
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"CANARYSYM","amount":1.123456789}
            """, "amount");
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"CANARYSYM","amount":120,"withholdingTax":-1}
            """, "withholdingTax");
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"CANARYSYM","amount":120,"note":"%s"}
            """.formatted("n".repeat(501)), "note");
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"AAPL","amount":120,"reversalOfId":"not-a-uuid"}
            """, "reversalOfId");
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"AAPL","amount":120,"paidAt":"2999-01-01T00:00:00Z"}
            """, "paidAt");
        /* 04.2 WR-04：帶號擴充年份在 Jackson 可解析、PostgreSQL 存不下；下限 1900-01-01 讓它回 400 而不是 500。 */
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"AAPL","amount":120,"paidAt":"-10000-01-01T00:00:00Z"}
            """, "paidAt");

        String originalId = dividendIdOf(postDividend(accessToken, UUID.randomUUID().toString(), AAPL_DIVIDEND)
            .andExpect(status().isOk()));
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"AAPL","amount":121,"withholdingTax":30,"reversalOfId":"%s"}
            """.formatted(originalId), "amount");
        assertFieldError(accessToken, canaryKey, """
            {"symbol":"AAPL","amount":120,"withholdingTax":31,"reversalOfId":"%s"}
            """.formatted(originalId), "withholdingTax");

        assertThat(dividendRows(email)).isEqualTo(1);
    }

    private void assertFieldError(String accessToken, String key, String body, String field) throws Exception {
        String response = postDividend(accessToken, key, body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields." + field, notNullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(response).doesNotContain("CANARYSYM").doesNotContain(key);
    }

    private static String reversalBody(String reversalOfId) {
        return """
            {"symbol":"AAPL","amount":120,"withholdingTax":30,"reversalOfId":"%s"}
            """.formatted(reversalOfId);
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
