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
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Backtest E2E")
class BacktestE2E extends AbstractStockE2ETest {

    @Autowired
    private AuthE2EHelper auth;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanUserData();
    }

    @Test
    @DisplayName("Authenticated user can create run, read result, and list runs")
    void authenticatedUserCanCreateRunReadResultAndListRuns() throws Exception {
        var session = auth.register("backtest-e2e@example.com", "backteste2e", "Password1");

        String createBody = mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("""
                    {"strategyId":"ma_cross","strategyCode":null,"symbol":"AAPL","period":"3Y","initialCapital":100000,"currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
                    """))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.status", equalTo("succeeded")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String runId = objectMapper.readTree(createBody).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/backtests/runs/{runId}/result", runId)
                .with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.kpis.tradeCount", equalTo(12)))
            .andExpect(jsonPath("$.data.equityCurve.length()", equalTo(12)))
            .andExpect(jsonPath("$.data.monthlyReturns.length()", equalTo(36)))
            .andExpect(jsonPath("$.data.drawdownCurve.length()", equalTo(12)))
            .andExpect(jsonPath("$.data.trades.length()", equalTo(12)));

        mockMvc.perform(get("/api/v1/backtests/runs?page=0&size=20")
                .with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)));
    }

    @Test
    @DisplayName("Custom strategy validation returns API response")
    void customStrategyValidationReturnsApiResponse() throws Exception {
        var session = auth.register("backtest-strategy-e2e@example.com", "backteststrategye2e", "Password1");

        mockMvc.perform(post("/api/v1/backtests/strategies/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .with(bearerToken(session.accessToken()))
                .content("""
                    {"strategyCode":"function strategy({ bars }) { return null; }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.valid", equalTo(true)));
    }
}
