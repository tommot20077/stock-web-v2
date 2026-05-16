package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiError;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Asset E2E")
class AssetE2E extends AbstractStockE2ETest {

    @Test
    @DisplayName("Public asset search returns seeded latest price data")
    void publicAssetSearchReturnsSeededLatestPriceData() throws Exception {
        mockMvc.perform(get("/api/v1/assets?query=NVDA&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.items.length()", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("NVDA")))
            .andExpect(jsonPath("$.data.items[0].latestPrice").value(1142.83));
    }

    @Test
    @DisplayName("Huge page request is clamped before querying")
    void hugePageRequestIsClamped() throws Exception {
        mockMvc.perform(get("/api/v1/assets?page=2147483647&size=100"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.page", equalTo(10000)))
            .andExpect(jsonPath("$.data.size", equalTo(100)));
    }

    @Test
    @DisplayName("Non-GET asset endpoint requires authentication")
    void nonGetAssetEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/assets"))
            .andExpect(status().isUnauthorized())
            .andExpect(apiError("AUTH_INVALID_CREDENTIALS"));
    }
}
