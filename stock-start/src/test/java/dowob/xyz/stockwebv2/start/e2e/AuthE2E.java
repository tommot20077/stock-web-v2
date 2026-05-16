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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Auth E2E")
class AuthE2E extends AbstractStockE2ETest {

    @Autowired
    private AuthE2EHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    @Test
    @DisplayName("Register, login, me, and logout use the real API and token flow")
    void registerLoginMeLogoutFlowWorks() throws Exception {
        var registered = authHelper.register("auth-e2e@example.com", "authe2e", "Password1");

        mockMvc.perform(get("/api/v1/me").with(bearerToken(registered.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.email", equalTo("auth-e2e@example.com")))
            .andExpect(jsonPath("$.data.role", equalTo("USER")))
            .andExpect(jsonPath("$.data.status", equalTo("ACTIVE")));

        var loggedIn = authHelper.login("auth-e2e@example.com", "Password1");

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(loggedIn.accessToken()))
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "refreshToken", loggedIn.refreshToken()
                ))))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data", notNullValue()));
    }

    @Test
    @DisplayName("Malformed bearer token returns the ApiResponse error envelope")
    void malformedBearerTokenReturnsApiResponse() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized())
            .andExpect(apiError("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("Blank refresh token returns validation error envelope")
    void blankRefreshTokenReturnsValidationError() throws Exception {
        var session = authHelper.register("blank-refresh-e2e@example.com", "blankrefreshe2e", "Password1");

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("{\"refreshToken\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(apiError("VALIDATION_FAILED"));
    }
}
