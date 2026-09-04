package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AssetApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void publicAssetsReturnsSeedAssetsWithLatestPrice() throws Exception {
        mockMvc.perform(get("/api/v1/assets?query=NVDA&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.items.length()", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("NVDA")))
            .andExpect(jsonPath("$.data.items[0].latestPrice").value(1142.83));
    }

    @Test
    void publicAssetsClampsHugePageBeforeQuerying() throws Exception {
        mockMvc.perform(get("/api/v1/assets?page=2147483647&size=100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.page", equalTo(10000)))
            .andExpect(jsonPath("$.data.size", equalTo(100)));
    }

    @Test
    void nonGetAssetPathRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/assets"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }
}
