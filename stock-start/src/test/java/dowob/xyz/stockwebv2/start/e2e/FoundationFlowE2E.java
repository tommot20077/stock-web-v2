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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Foundation Flow E2E")
class FoundationFlowE2E extends AbstractStockE2ETest {

    @Autowired
    private AuthE2EHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    @Test
    @DisplayName("User can authenticate, read public assets, logout, and keep public access")
    void authenticatedUserCanReadAssetsAndLogout() throws Exception {
        var session = authHelper.register("flow-e2e@example.com", "flowe2e", "Password1");

        mockMvc.perform(get("/api/v1/me").with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.email", equalTo("flow-e2e@example.com")));

        mockMvc.perform(get("/api/v1/assets?query=AAPL&page=0&size=10")
                .with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.items.length()", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("AAPL")));

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "refreshToken", session.refreshToken()
                ))))
            .andExpect(status().isOk())
            .andExpect(apiSuccess());

        mockMvc.perform(get("/api/v1/assets?query=AAPL&page=0&size=10"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess());

        mockMvc.perform(post("/api/v1/assets"))
            .andExpect(status().isUnauthorized())
            .andExpect(apiError("AUTH_INVALID_CREDENTIALS"));
    }
}
