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
 * <p>本檔先鎖「入金一筆寫入並回 DTO」與「出金不受餘額限制」兩條主線（D-01 / D-08）；
 * 冪等重送、payload 衝突、欄位驗證與稽核由 04.1-22 擴充至同一個類別。</p>
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
