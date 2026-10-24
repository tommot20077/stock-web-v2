package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;

/**
 * 使用者基準幣偏好（PORT-09）的端到端驗收：{@code GET /api/v1/me} 的預設值與 V14 欄位約束。
 *
 * <p>需要 Docker（Testcontainers），僅能在 CI Integration job 執行。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("使用者基準幣偏好")
class UserPreferencesIT extends ContainerIT {
    private static final String PASSWORD = "Password1";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("新使用者的 /me 回傳預設基準幣 TWD")
    void newUserMeReturnsTwdBaseCurrency() throws Exception {
        String token = registerAndLogin("pref-default@example.com", "prefdefault");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }

    @Test
    @DisplayName("users.base_currency 為 NOT NULL、預設 TWD，且受 CHECK 約束保護")
    void baseCurrencyColumnHasDefaultAndCheckConstraint() throws Exception {
        registerAndLogin("pref-column@example.com", "prefcolumn");

        String nullable = jdbcClient.sql("""
                select is_nullable from information_schema.columns
                where table_name = 'users' and column_name = 'base_currency'
                """)
            .query(String.class)
            .single();
        String columnDefault = jdbcClient.sql("""
                select column_default from information_schema.columns
                where table_name = 'users' and column_name = 'base_currency'
                """)
            .query(String.class)
            .single();

        assertThat(nullable).isEqualTo("NO");
        assertThat(columnDefault).contains("TWD");
        assertThatThrownBy(() -> jdbcClient.sql("update users set base_currency = 'GBP' where email = :email")
            .param("email", "pref-column@example.com")
            .update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String registerAndLogin(String email, String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, PASSWORD)))
            .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }
}
