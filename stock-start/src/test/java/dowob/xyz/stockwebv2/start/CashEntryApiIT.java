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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
 * {@code POST /api/v1/portfolio/cash-entries} 的端到端整合測試（CASH-01 tracer）。
 *
 * <p>先鎖「入金一筆寫入並回 DTO」與「出金不受餘額限制」兩條主線（D-01 / D-08），
 * 再鎖冪等語意（D-11）：同 key 同 payload 回既有、改 payload 回 409、只改 note 視為同一筆、
 * 省略 occurredAt 的重送仍命中、跨使用者隔離；最後是輸入邊界：欄位級錯誤（{@code error.fields}）、
 * 錯誤不回射使用者輸入、cookie 登入未帶 CSRF 被擋、格式錯誤的 JSON body 回 400。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金分錄 API")
@AutoConfigureMockMvc
class CashEntryApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("入金一筆：回 200 與 DTO（id 為 UUID、金額為 JSON number、不含冪等鍵），資料庫恰一列")
    void depositIsRecordedAndReturnedWithoutIdempotencyKey() throws Exception {
        String email = "cash-api-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashapi" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":50000,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", hasLength(36)))
            .andExpect(jsonPath("$.data.type", equalTo("DEPOSIT")))
            .andExpect(jsonPath("$.data.currency", equalTo("TWD")))
            .andExpect(jsonPath("$.data.amount", equalTo(50000.00000000)))
            .andExpect(jsonPath("$.data.reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.reversalOfId").value(nullValue()))
            .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());

        Integer rows = jdbcClient.sql("""
                SELECT count(*) FROM cash_ledger_entries
                WHERE user_id = (SELECT id FROM users WHERE email = :email)
                """)
            .param("email", email)
            .query(Integer.class)
            .single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("新使用者直接出金 USD 1000：照常記帳回 200（D-01 / D-08 不驗證餘額）")
    void withdrawalLargerThanAnyBalanceIsStillRecorded() throws Exception {
        String accessToken = register(
            "cash-api-" + UUID.randomUUID() + "@example.com",
            "cashapi" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"WITHDRAWAL","currency":"USD","amount":1000}
            """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("WITHDRAWAL")))
            .andExpect(jsonPath("$.data.currency", equalTo("USD")))
            .andExpect(jsonPath("$.data.amount", equalTo(1000.00000000)));
    }

    @Test
    @DisplayName("同 key 同 payload 送兩次：回同一筆（同 id）、帳本只有 1 列")
    void sameKeySamePayloadReturnsExistingEntryAndLedgerHasOneRow() throws Exception {
        String email = "cash-idem-same-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashidem" + UUID.randomUUID().toString().substring(0, 8));
        String key = "same-" + UUID.randomUUID();
        String body = """
            {"type":"DEPOSIT","currency":"TWD","amount":50000,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """;

        String firstId = entryIdOf(postEntry(accessToken, key, body).andExpect(status().isOk()));
        String secondId = entryIdOf(postEntry(accessToken, key, body).andExpect(status().isOk()));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(rowCount(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("同 key 改 amount：409 CASH_IDEMPOTENCY_KEY_REUSED，帳本仍只有 1 列")
    void sameKeyDifferentAmountIsRejectedAndDoesNotAddRow() throws Exception {
        String email = "cash-idem-diff-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashidem" + UUID.randomUUID().toString().substring(0, 8));
        String key = "diff-" + UUID.randomUUID();

        postEntry(accessToken, key, """
            {"type":"DEPOSIT","currency":"TWD","amount":50000,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """).andExpect(status().isOk());

        postEntry(accessToken, key, """
            {"type":"DEPOSIT","currency":"TWD","amount":50001,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("CASH_IDEMPOTENCY_KEY_REUSED")));

        assertThat(rowCount(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("同 key 只改 note：視為同一筆，回 200 同 id（備註不納入比對）")
    void sameKeyWithOnlyDifferentNoteReturnsSameEntry() throws Exception {
        String email = "cash-idem-note-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashidem" + UUID.randomUUID().toString().substring(0, 8));
        String key = "note-" + UUID.randomUUID();

        String firstId = entryIdOf(postEntry(accessToken, key, """
            {"type":"DEPOSIT","currency":"USD","amount":1200.5,"note":"first","occurredAt":"2026-10-01T09:00:00+08:00"}
            """).andExpect(status().isOk()));
        String secondId = entryIdOf(postEntry(accessToken, key, """
            {"type":"DEPOSIT","currency":"USD","amount":1200.5,"note":"edited before retry","occurredAt":"2026-10-01T09:00:00+08:00"}
            """).andExpect(status().isOk()));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(rowCount(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("兩位使用者用同一把 key：各自建立分錄（key 以使用者隔離）")
    void sameKeyAcrossUsersCreatesOneEntryPerUser() throws Exception {
        String emailA = "cash-idem-a-" + UUID.randomUUID() + "@example.com";
        String emailB = "cash-idem-b-" + UUID.randomUUID() + "@example.com";
        String tokenA = register(emailA, "cashidema" + UUID.randomUUID().toString().substring(0, 8));
        String tokenB = register(emailB, "cashidemb" + UUID.randomUUID().toString().substring(0, 8));
        String key = "shared-" + UUID.randomUUID();
        String body = """
            {"type":"DEPOSIT","currency":"TWD","amount":100,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """;

        String idA = entryIdOf(postEntry(tokenA, key, body).andExpect(status().isOk()));
        String idB = entryIdOf(postEntry(tokenB, key, body).andExpect(status().isOk()));

        assertThat(idB).isNotEqualTo(idA);
        assertThat(rowCount(emailA)).isEqualTo(1);
        assertThat(rowCount(emailB)).isEqualTo(1);
    }

    @Test
    @DisplayName("首次省略 occurredAt、重送也省略：同 key 仍命中原分錄（伺服器首次決定的時間成立）")
    void resendWithoutOccurredAtStillHitsOriginalEntry() throws Exception {
        String email = "cash-idem-omit-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashidem" + UUID.randomUUID().toString().substring(0, 8));
        String key = "omit-" + UUID.randomUUID();
        String body = """
            {"type":"WITHDRAWAL","currency":"JPY","amount":3000}
            """;

        String firstId = entryIdOf(postEntry(accessToken, key, body).andExpect(status().isOk()));
        String secondId = entryIdOf(postEntry(accessToken, key, body).andExpect(status().isOk()));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(rowCount(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key header：400 VALIDATION_FAILED，fields 指名 Idempotency-Key，帳本無新增")
    void missingIdempotencyKeyHeaderNamesTheHeader() throws Exception {
        String email = "cash-val-nokey-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashval" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/portfolio/cash-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content("""
                    {"type":"DEPOSIT","currency":"TWD","amount":100}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']").exists());

        assertThat(rowCount(email)).isZero();
    }

    @Test
    @DisplayName("Idempotency-Key 129 字元：400，fields 指名 Idempotency-Key，帳本無新增")
    void oversizedIdempotencyKeyNamesTheHeader() throws Exception {
        String email = "cash-val-longkey-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashval" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, "k".repeat(129), """
            {"type":"DEPOSIT","currency":"TWD","amount":100}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields['Idempotency-Key']").exists());

        assertThat(rowCount(email)).isZero();
    }

    @Test
    @DisplayName("currency GBP：400，fields.currency")
    void unsupportedCurrencyNamesTheField() throws Exception {
        String accessToken = register(
            "cash-val-ccy-" + UUID.randomUUID() + "@example.com",
            "cashval" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"GBP","amount":100}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.currency").exists());
    }

    @Test
    @DisplayName("type TRANSFER：400，fields.type")
    void unsupportedTypeNamesTheField() throws Exception {
        String accessToken = register(
            "cash-val-type-" + UUID.randomUUID() + "@example.com",
            "cashval" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"TRANSFER","currency":"TWD","amount":100}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.type").exists());
    }

    @Test
    @DisplayName("amount 0：400，fields.amount，帳本無新增")
    void zeroAmountNamesTheField() throws Exception {
        String email = "cash-val-amt-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashval" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":0}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.amount").exists());

        assertThat(rowCount(email)).isZero();
    }

    @Test
    @DisplayName("occurredAt 為未來（+1 天）：400，fields.occurredAt，帳本無新增")
    void futureOccurredAtNamesTheField() throws Exception {
        String email = "cash-val-future-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashval" + UUID.randomUUID().toString().substring(0, 8));
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();

        postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":100,"occurredAt":"%s"}
            """.formatted(future))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.occurredAt", equalTo("must not be in the future")));

        assertThat(rowCount(email)).isZero();
    }

    @Test
    @DisplayName("所有錯誤回應的完整 body 都不回射使用者送出的 key 或備註（canary）")
    void errorResponsesNeverEchoUserControlledInput() throws Exception {
        String accessToken = register(
            "cash-val-canary-" + UUID.randomUUID() + "@example.com",
            "cashval" + UUID.randomUUID().toString().substring(0, 8));
        String canaryNote = "CANARY-NOTE-" + UUID.randomUUID();
        String canaryKey = "CANARY-KEY-" + UUID.randomUUID();

        // 先成功建立一筆，才能製造同 key 不同 payload 的 409。
        postEntry(accessToken, canaryKey, """
            {"type":"DEPOSIT","currency":"TWD","amount":100,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """).andExpect(status().isOk());

        List<String> errorBodies = new ArrayList<>();
        errorBodies.add(postEntry(accessToken, canaryKey, """
            {"type":"DEPOSIT","currency":"TWD","amount":101,"note":"%s","occurredAt":"2026-10-01T09:00:00+08:00"}
            """.formatted(canaryNote)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        errorBodies.add(postEntry(accessToken, canaryKey + "x".repeat(130), """
            {"type":"DEPOSIT","currency":"TWD","amount":100,"note":"%s"}
            """.formatted(canaryNote)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        errorBodies.add(postEntry(accessToken, canaryKey + "-gbp", """
            {"type":"DEPOSIT","currency":"GBP","amount":100,"note":"%s"}
            """.formatted(canaryNote)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        errorBodies.add(postEntry(accessToken, canaryKey + "-future", """
            {"type":"DEPOSIT","currency":"TWD","amount":100,"note":"%s","occurredAt":"%s"}
            """.formatted(canaryNote, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        errorBodies.add(postEntry(accessToken, "   ", """
            {"type":"DEPOSIT","currency":"TWD","amount":100,"note":"%s"}
            """.formatted(canaryNote)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());

        assertThat(errorBodies).hasSize(5).allSatisfy(body -> assertThat(body)
            .doesNotContain("CANARY-KEY-")
            .doesNotContain("CANARY-NOTE-"));
    }

    @Test
    @DisplayName("cookie 登入但未帶 CSRF header 的 POST：403 AUTH_CSRF_TOKEN_INVALID，帳本無新增（negative test）")
    void cookieAuthenticatedPostWithoutCsrfIsForbidden() throws Exception {
        String email = "cash-csrf-" + UUID.randomUUID() + "@example.com";
        register(email, "cashcsrf" + UUID.randomUUID().toString().substring(0, 8));
        Cookie accessCookie = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"Password1"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("stock_access");

        mockMvc.perform(post("/api/v1/portfolio/cash-entries")
                .cookie(accessCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("""
                    {"type":"DEPOSIT","currency":"TWD","amount":100}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        assertThat(rowCount(email)).isZero();
    }

    @Test
    @DisplayName("格式錯誤的 JSON body：400 VALIDATION_FAILED（D-27，不是 500）")
    void malformedJsonBodyIsBadRequest() throws Exception {
        String email = "cash-val-json-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashval" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(), "{\"type\":")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));

        assertThat(rowCount(email)).isZero();
    }

    @Test
    @DisplayName("列表：只含自己的分錄，occurredAt 新到舊（刻意與插入順序不一致），totalElements / totalPages 正確")
    void listReturnsOwnEntriesNewestFirstWithTotals() throws Exception {
        String accessToken = register(
            "cash-list-order-" + UUID.randomUUID() + "@example.com",
            "cashlist" + UUID.randomUUID().toString().substring(0, 8));

        // 插入順序：中間、最新、最舊；列表必須依 occurredAt 而非插入順序排列。
        String middleId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":200,"occurredAt":"2026-10-02T09:00:00+08:00"}
            """).andExpect(status().isOk()));
        String newestId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":300,"occurredAt":"2026-10-03T09:00:00+08:00"}
            """).andExpect(status().isOk()));
        String oldestId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), """
            {"type":"WITHDRAWAL","currency":"USD","amount":100,"occurredAt":"2026-10-01T09:00:00+08:00"}
            """).andExpect(status().isOk()));

        getEntries(accessToken, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.totalPages", equalTo(1)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(3)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(newestId)))
            .andExpect(jsonPath("$.data.items[1].id", equalTo(middleId)))
            .andExpect(jsonPath("$.data.items[2].id", equalTo(oldestId)))
            .andExpect(jsonPath("$.data.items[0].reversed", equalTo(false)))
            .andExpect(jsonPath("$.data.items[0].idempotencyKey").doesNotExist());

        getEntries(accessToken, "0", "2")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.data.totalPages", equalTo(2)))
            .andExpect(jsonPath("$.data.items.length()", equalTo(2)));
        getEntries(accessToken, "1", "2")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(oldestId)));
    }

    @Test
    @DisplayName("列表：絕不出現其他使用者的分錄，也不計入他們的 totalElements（T-04.1-22）")
    void listNeverLeaksOtherUsersEntries() throws Exception {
        String tokenA = register(
            "cash-list-a-" + UUID.randomUUID() + "@example.com",
            "cashlista" + UUID.randomUUID().toString().substring(0, 8));
        String tokenB = register(
            "cash-list-b-" + UUID.randomUUID() + "@example.com",
            "cashlistb" + UUID.randomUUID().toString().substring(0, 8));
        String idA = entryIdOf(postEntry(tokenA, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":111}
            """).andExpect(status().isOk()));
        String idB = entryIdOf(postEntry(tokenB, UUID.randomUUID().toString(), """
            {"type":"DEPOSIT","currency":"TWD","amount":222}
            """).andExpect(status().isOk()));

        String bodyA = getEntries(tokenA, null, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andExpect(jsonPath("$.data.items[0].id", equalTo(idA)))
            .andReturn().getResponse().getContentAsString();
        assertThat(bodyA).doesNotContain(idB);
        String bodyB = getEntries(tokenB, null, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
            .andReturn().getResponse().getContentAsString();
        assertThat(bodyB).doesNotContain(idA);
    }

    @Test
    @DisplayName("列表：新使用者 items 為空、totalElements 為 0")
    void listForNewUserIsEmpty() throws Exception {
        String accessToken = register(
            "cash-list-empty-" + UUID.randomUUID() + "@example.com",
            "cashlist" + UUID.randomUUID().toString().substring(0, 8));

        getEntries(accessToken, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()", equalTo(0)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(0)))
            .andExpect(jsonPath("$.data.totalPages", equalTo(0)));
    }

    @Test
    @DisplayName("列表：page=abc 回 400 VALIDATION_FAILED（不是 500）")
    void nonNumericPageIsRejected() throws Exception {
        String accessToken = register(
            "cash-list-badpage-" + UUID.randomUUID() + "@example.com",
            "cashlist" + UUID.randomUUID().toString().substring(0, 8));

        getEntries(accessToken, "abc", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
        getEntries(accessToken, null, "abc")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    // ── 一鍵沖銷（D-25）────────────────────────────────────────────────────────

    private static final String DEPOSIT_USD_1000 = """
        {"type":"DEPOSIT","currency":"USD","amount":1000,"occurredAt":"2026-10-01T09:00:00+08:00"}
        """;

    private static String reversalBody(String type, String currency, String amount, String originalId) {
        return """
            {"type":"%s","currency":"%s","amount":%s,"reversalOfId":"%s"}
            """.formatted(type, currency, amount, originalId);
    }

    @Test
    @DisplayName("沖銷：方向相反、幣別金額相同，成功後原分錄在列表 reversed = true，沖銷分錄指回原分錄")
    void reversalMirrorsTheOriginalAndMarksItReversed() throws Exception {
        String email = "cash-rev-ok-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));

        String reversalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(),
            reversalBody("WITHDRAWAL", "USD", "1000", originalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type", equalTo("WITHDRAWAL")))
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(originalId)))
            .andExpect(jsonPath("$.data.reversalOfOccurredAt").exists())
            .andExpect(jsonPath("$.data.reversed", equalTo(false))));

        assertThat(rowCount(email)).isEqualTo(2);
        getEntries(accessToken, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.data.items[?(@.id=='" + originalId + "')].reversed", contains(true)))
            .andExpect(jsonPath("$.data.items[?(@.id=='" + reversalId + "')].reversed", contains(false)))
            .andExpect(jsonPath("$.data.items[?(@.id=='" + reversalId + "')].reversalOfId", contains(originalId)));
    }

    @Test
    @DisplayName("沖銷：同一筆以另一把 key 再沖銷 → 409 CASH_ENTRY_ALREADY_REVERSED，帳本仍 2 列")
    void reversingTheSameEntryTwiceIsRejected() throws Exception {
        String email = "cash-rev-twice-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));
        postEntry(accessToken, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "USD", "1000", originalId))
            .andExpect(status().isOk());

        postEntry(accessToken, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "USD", "1000", originalId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("CASH_ENTRY_ALREADY_REVERSED")));

        assertThat(rowCount(email)).isEqualTo(2);
    }

    @Test
    @DisplayName("沖銷：沖銷「沖銷分錄」→ 409 CASH_ENTRY_NOT_REVERSIBLE")
    void reversingAReversalIsRejected() throws Exception {
        String email = "cash-rev-chain-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));
        String reversalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(),
            reversalBody("WITHDRAWAL", "USD", "1000", originalId)).andExpect(status().isOk()));

        postEntry(accessToken, UUID.randomUUID().toString(), reversalBody("DEPOSIT", "USD", "1000", reversalId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code", equalTo("CASH_ENTRY_NOT_REVERSIBLE")));

        assertThat(rowCount(email)).isEqualTo(2);
    }

    @Test
    @DisplayName("沖銷：user B 沖銷 user A 的分錄 → 404 CASH_ENTRY_NOT_FOUND，body 不含該 UUID（不洩漏存在性）")
    void reversingAnotherUsersEntryIsNotFound() throws Exception {
        String tokenA = register(
            "cash-rev-a-" + UUID.randomUUID() + "@example.com",
            "cashreva" + UUID.randomUUID().toString().substring(0, 8));
        String emailB = "cash-rev-b-" + UUID.randomUUID() + "@example.com";
        String tokenB = register(emailB, "cashrevb" + UUID.randomUUID().toString().substring(0, 8));
        String originalIdOfA = entryIdOf(postEntry(tokenA, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));

        String body = postEntry(tokenB, UUID.randomUUID().toString(),
            reversalBody("WITHDRAWAL", "USD", "1000", originalIdOfA))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("CASH_ENTRY_NOT_FOUND")))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(originalIdOfA);
        assertThat(rowCount(emailB)).isZero();
    }

    @Test
    @DisplayName("沖銷：隨機（不存在的）UUID → 404 CASH_ENTRY_NOT_FOUND")
    void reversingAnUnknownEntryIsNotFound() throws Exception {
        String accessToken = register(
            "cash-rev-unknown-" + UUID.randomUUID() + "@example.com",
            "cashrev" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(),
            reversalBody("WITHDRAWAL", "USD", "1000", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("CASH_ENTRY_NOT_FOUND")));
    }

    @Test
    @DisplayName("沖銷：reversalOfId 不是 UUID → 400，fields.reversalOfId = invalid format")
    void malformedReversalOfIdNamesTheField() throws Exception {
        String accessToken = register(
            "cash-rev-badid-" + UUID.randomUUID() + "@example.com",
            "cashrev" + UUID.randomUUID().toString().substring(0, 8));

        postEntry(accessToken, UUID.randomUUID().toString(),
            reversalBody("WITHDRAWAL", "USD", "1000", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("invalid format")));
    }

    @Test
    @DisplayName("沖銷：金額 999（不等於原分錄 1000）→ 400，fields.reversalOfId = must mirror the original entry")
    void reversalWithDifferentAmountNamesTheField() throws Exception {
        String email = "cash-rev-amt-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));

        postEntry(accessToken, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "USD", "999", originalId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("must mirror the original entry")));

        assertThat(rowCount(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("沖銷：方向相同或幣別不同 → 400，fields.reversalOfId = must mirror the original entry")
    void reversalWithSameDirectionOrDifferentCurrencyNamesTheField() throws Exception {
        String email = "cash-rev-dir-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));

        postEntry(accessToken, UUID.randomUUID().toString(), reversalBody("DEPOSIT", "USD", "1000", originalId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("must mirror the original entry")));
        postEntry(accessToken, UUID.randomUUID().toString(), reversalBody("WITHDRAWAL", "TWD", "1000", originalId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.fields.reversalOfId", equalTo("must mirror the original entry")));

        assertThat(rowCount(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("沖銷：同一把 key 重送已成功的沖銷 → 200 同一 id（冪等優先於「已沖銷」），帳本仍 2 列")
    void replayOfASuccessfulReversalReturnsTheSameEntry() throws Exception {
        String email = "cash-rev-replay-" + UUID.randomUUID() + "@example.com";
        String accessToken = register(email, "cashrev" + UUID.randomUUID().toString().substring(0, 8));
        String originalId = entryIdOf(postEntry(accessToken, UUID.randomUUID().toString(), DEPOSIT_USD_1000)
            .andExpect(status().isOk()));
        String key = "rev-replay-" + UUID.randomUUID();
        String body = reversalBody("WITHDRAWAL", "USD", "1000", originalId);

        String firstId = entryIdOf(postEntry(accessToken, key, body).andExpect(status().isOk()));
        String secondId = entryIdOf(postEntry(accessToken, key, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reversalOfId", equalTo(originalId))));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(rowCount(email)).isEqualTo(2);
    }

    private ResultActions getEntries(String accessToken, String page, String size) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/portfolio/cash-entries")
            .header("Authorization", "Bearer " + accessToken);
        if (page != null) {
            request = request.param("page", page);
        }
        if (size != null) {
            request = request.param("size", size);
        }
        return mockMvc.perform(request);
    }

    private String entryIdOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asText();
    }

    private int rowCount(String email) {
        Integer rows = jdbcClient.sql("""
                SELECT count(*) FROM cash_ledger_entries
                WHERE user_id = (SELECT id FROM users WHERE email = :email)
                """)
            .param("email", email)
            .query(Integer.class)
            .single();
        return rows;
    }

    private ResultActions postEntry(String accessToken, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/portfolio/cash-entries")
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
