package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.user.UserPreferenceFacade;
import dowob.xyz.stockwebv2.start.support.ContainerIT;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    private static final String ACCESS_COOKIE = "stock_access";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserPreferenceFacade userPreferenceFacade;

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

    @Test
    @DisplayName("bearer PATCH 修改基準幣，/me 反映新值")
    void patchWithBearerUpdatesBaseCurrencyAndMeReflectsIt() throws Exception {
        String token = registerAndLogin("pref-bearer@example.com", "prefbearer");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"USD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("USD")));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("USD")));
    }

    @Test
    @DisplayName("不支援的幣別回 400 並指名欄位，且不回射輸入")
    void unsupportedCurrencyIsRejectedWithoutEcho() throws Exception {
        String token = registerAndLogin("pref-reject@example.com", "prefreject");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"PREF-CANARY\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.error.fields.baseCurrency").exists())
            .andExpect(content().string(not(containsString("PREF-CANARY"))));
    }

    @Test
    @DisplayName("cookie PATCH 缺 CSRF header 被 403 擋下且值不變")
    void cookiePatchWithoutCsrfIsForbidden() throws Exception {
        Cookie access = registerAndLoginWithCookie("pref-nocsrf@example.com", "prefnocsrf");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"USD\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_CSRF_TOKEN_INVALID")));

        mockMvc.perform(get("/api/v1/me").cookie(access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }

    @Test
    @DisplayName("cookie PATCH 帶 CSRF header 成功")
    void cookiePatchWithCsrfSucceeds() throws Exception {
        Cookie access = registerAndLoginWithCookie("pref-csrf@example.com", "prefcsrf");
        Cookie xsrf = mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie(XSRF_COOKIE);

        mockMvc.perform(patch("/api/v1/me/preferences")
                .cookie(access)
                .cookie(xsrf)
                .header(XSRF_HEADER, xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"JPY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("JPY")));
    }

    @Test
    @DisplayName("未認證 PATCH 回 401")
    void unauthenticatedPatchIsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"USD\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("格式錯誤的 JSON 回 400 VALIDATION_FAILED")
    void malformedJsonPatchIsBadRequest() throws Exception {
        String token = registerAndLogin("pref-malformed@example.com", "prefmalformed");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Facade 立即讀到 PATCH 寫入的基準幣，查無使用者回 TWD")
    void facadeReadsBaseCurrencyWrittenByPatch() throws Exception {
        String token = registerAndLogin("pref-facade@example.com", "preffacade");
        String meBody = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long userId = objectMapper.readTree(meBody).get("data").get("id").asLong();

        assertThat(userPreferenceFacade.findBaseCurrency(userId)).isEqualTo(CurrencyCode.TWD);

        mockMvc.perform(patch("/api/v1/me/preferences")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"USD\"}"))
            .andExpect(status().isOk());

        assertThat(userPreferenceFacade.findBaseCurrency(userId)).isEqualTo(CurrencyCode.USD);
        assertThat(userPreferenceFacade.findBaseCurrency(Long.MAX_VALUE)).isEqualTo(CurrencyCode.TWD);
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

    private Cookie registerAndLoginWithCookie(String email, String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, PASSWORD)))
            .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
        return login.getResponse().getCookie(ACCESS_COOKIE);
    }
}
