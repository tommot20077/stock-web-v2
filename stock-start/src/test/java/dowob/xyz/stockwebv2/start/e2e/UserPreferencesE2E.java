package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper;
import dowob.xyz.stockwebv2.start.e2e.support.DatabaseCleaner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用者基準幣偏好（PORT-09）的 REST 層 E2E：預設值、修改、持久化與輸入邊界。
 *
 * <p>契約見 {@code ai-docs/trading-portfolio-contract.md} 與 {@code ai-docs/browser-auth-contract.md}：
 * {@code GET /api/v1/me} 帶 {@code baseCurrency}（新註冊預設 TWD）、{@code PATCH /api/v1/me/preferences}
 * 只接受 {@code CurrencyCode} 白名單、錯誤走 {@code VALIDATION_FAILED} 且不回射輸入。
 * 全部走真實 Spring 應用與真實 Postgres / Redis，身分由 {@link AuthE2EHelper} 取得。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("User Preferences E2E")
class UserPreferencesE2E extends AbstractStockE2ETest {

    @Autowired
    private AuthE2EHelper auth;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    @Test
    @DisplayName("新使用者 /me 的 baseCurrency 預設為 TWD")
    void newUserBaseCurrencyDefaultsToTwd() throws Exception {
        var session = auth.register("pref-e2e-default@example.com", "prefe2edefault", "Password1");

        mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.email", equalTo("pref-e2e-default@example.com")))
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }

    @Test
    @DisplayName("PATCH 改為 USD：回應、重讀 /me 與重新登入後的 /me 都是 USD，且不影響其他使用者")
    void patchToUsdPersistsAcrossReadsAndFreshLogin() throws Exception {
        var session = auth.register("pref-e2e-persist@example.com", "prefe2epersist", "Password1");
        var other = auth.register("pref-e2e-other@example.com", "prefe2eother", "Password1");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("{\"baseCurrency\":\"USD\"}"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("USD")))
            .andExpect(jsonPath("$.data.email", equalTo("pref-e2e-persist@example.com")));

        mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("USD")));

        var freshLogin = auth.login("pref-e2e-persist@example.com", "Password1");
        mockMvc.perform(get("/api/v1/me").with(bearerToken(freshLogin.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("USD")));

        mockMvc.perform(get("/api/v1/me").with(bearerToken(other.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }

    @Test
    @DisplayName("四種支援幣別都能設定，且大小寫與前後空白會被正規化")
    void everySupportedCurrencyCanBeSelectedAndInputIsNormalized() throws Exception {
        var session = auth.register("pref-e2e-all@example.com", "prefe2eall", "Password1");

        for (String requested : new String[] {"EUR", "JPY", "TWD", "USD", " eur "}) {
            String expected = requested.trim().toUpperCase();
            mockMvc.perform(patch("/api/v1/me/preferences")
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content("{\"baseCurrency\":\"" + requested + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseCurrency", equalTo(expected)));

            mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseCurrency", equalTo(expected)));
        }
    }

    @Test
    @DisplayName("不支援的幣別回 400 VALIDATION_FAILED 並指名 baseCurrency 欄位，不回射輸入，值不變")
    void unsupportedCurrencyIsRejectedWithoutEchoAndKeepsValue() throws Exception {
        var session = auth.register("pref-e2e-reject@example.com", "prefe2ereject", "Password1");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("{\"baseCurrency\":\"PREF-E2E-CANARY\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields.baseCurrency").exists())
            .andExpect(content().string(not(containsString("PREF-E2E-CANARY"))));

        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("{\"baseCurrency\":\"GBP\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fields.baseCurrency").exists());

        mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }

    @Test
    @DisplayName("空字串、純空白與缺少欄位都回 400 VALIDATION_FAILED 並指名 baseCurrency 欄位")
    void blankOrMissingCurrencyIsRejected() throws Exception {
        var session = auth.register("pref-e2e-blank@example.com", "prefe2eblank", "Password1");

        for (String body : new String[] {"{\"baseCurrency\":\"\"}", "{\"baseCurrency\":\"   \"}", "{}"}) {
            mockMvc.perform(patch("/api/v1/me/preferences")
                    .contentType(MediaType.APPLICATION_JSON)
                    .with(bearerToken(session.accessToken()))
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.baseCurrency").exists());
        }

        mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }

    @Test
    @DisplayName("格式錯誤的 JSON body 回 400 VALIDATION_FAILED（不是 500）")
    void malformedJsonBodyIsBadRequest() throws Exception {
        var session = auth.register("pref-e2e-json@example.com", "prefe2ejson", "Password1");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("{\"baseCurrency\":"))
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("未認證的 PATCH 與 GET /me 都回 401，且不會修改任何使用者")
    void unauthenticatedRequestsAreUnauthorized() throws Exception {
        var session = auth.register("pref-e2e-anon@example.com", "prefe2eanon", "Password1");

        mockMvc.perform(patch("/api/v1/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseCurrency\":\"USD\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)));

        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)));

        mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseCurrency", equalTo("TWD")));
    }
}
