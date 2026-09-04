package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class BacktestApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void backtestEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRunBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }

    @Test
    void authenticatedUserCanCreateReadAndListOwnRun() throws Exception {
        AuthTokens tokens = register("backtest-owner@example.com", "backtestowner", "Password1");

        String createBody = mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(validRunBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.status", equalTo("succeeded")))
            .andExpect(jsonPath("$.data.id", notNullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String runId = objectMapper.readTree(createBody).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/backtests/runs/{runId}", runId)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")));

        mockMvc.perform(get("/api/v1/backtests/runs/{runId}/result", runId)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.tradeCount", equalTo(12)))
            .andExpect(jsonPath("$.data.trades.length()", equalTo(12)));

        mockMvc.perform(get("/api/v1/backtests/runs?page=0&size=20&symbol=AAPL")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
    }

    @Test
    void authenticatedUserCanValidateStrategy() throws Exception {
        AuthTokens tokens = register("backtest-validator@example.com", "backtestvalidator", "Password1");

        mockMvc.perform(post("/api/v1/backtests/strategies/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("""
                    {"strategyCode":"function strategy({ bars }) { return null; }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.valid", equalTo(true)));
    }

    @Test
    void createRunRejectsInvalidBodyWithValidationEnvelope() throws Exception {
        AuthTokens tokens = register("backtest-invalid-body@example.com", "backtestinvalidbody", "Password1");

        mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("""
                    {"strategyId":"ma_cross","strategyCode":null,"symbol":"","period":"3Y","initialCapital":100000,"currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    void listRunsRejectsNonNumericPageWithValidationEnvelope() throws Exception {
        AuthTokens tokens = register("backtest-bad-page@example.com", "backtestbadpage", "Password1");

        mockMvc.perform(get("/api/v1/backtests/runs?page=abc&size=20")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    void usersCannotReadOtherUsersRuns() throws Exception {
        AuthTokens owner = register("backtest-private-owner@example.com", "backtestprivateowner", "Password1");
        AuthTokens other = register("backtest-private-other@example.com", "backtestprivateother", "Password1");

        String createBody = mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + owner.accessToken())
                .content(validRunBody()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String runId = objectMapper.readTree(createBody).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/backtests/runs/{runId}", runId)
                .header("Authorization", "Bearer " + other.accessToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("BACKTEST_RUN_NOT_FOUND")));
    }

    @Test
    void usersCannotReadOtherUsersResults() throws Exception {
        AuthTokens owner = register("backtest-result-owner@example.com", "backtestresultowner", "Password1");
        AuthTokens other = register("backtest-result-other@example.com", "backtestresultother", "Password1");

        String runId = createRun(owner);

        mockMvc.perform(get("/api/v1/backtests/runs/{runId}/result", runId)
                .header("Authorization", "Bearer " + other.accessToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("BACKTEST_RUN_NOT_FOUND")));
    }

    @Test
    void usersCannotListOtherUsersRuns() throws Exception {
        AuthTokens owner = register("backtest-list-owner@example.com", "backtestlistowner", "Password1");
        AuthTokens other = register("backtest-list-other@example.com", "backtestlistother", "Password1");
        createRun(owner);

        mockMvc.perform(get("/api/v1/backtests/runs?page=0&size=20")
                .header("Authorization", "Bearer " + other.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()", equalTo(0)));
    }

    private String validRunBody() {
        return """
            {"strategyId":"ma_cross","strategyCode":null,"symbol":"AAPL","period":"3Y","initialCapital":100000,"currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
            """;
    }

    private String createRun(AuthTokens tokens) throws Exception {
        String createBody = mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content(validRunBody()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(createBody).get("data").get("id").asText();
    }

    private AuthTokens register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return readTokens(body);
    }

    private AuthTokens readTokens(String body) throws Exception {
        JsonNode data = objectMapper.readTree(body).get("data");
        return new AuthTokens(data.get("accessToken").asText(), data.get("refreshToken").asText());
    }

    private record AuthTokens(String accessToken, String refreshToken) {
    }
}
