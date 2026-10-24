package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

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
 * {@code POST /api/v1/portfolio/cash-entries} 的端到端整合測試（CASH-01 tracer）。
 *
 * <p>先鎖「入金一筆寫入並回 DTO」與「出金不受餘額限制」兩條主線（D-01 / D-08），
 * 再鎖冪等語意（D-11）：同 key 同 payload 回既有、改 payload 回 409、只改 note 視為同一筆、
 * 省略 occurredAt 的重送仍命中、跨使用者隔離。</p>
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
