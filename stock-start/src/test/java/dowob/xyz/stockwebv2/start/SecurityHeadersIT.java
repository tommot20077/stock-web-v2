package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * HTTP 安全標頭(security.md §19;2026-09-02 安全審查 L-5)。
 *
 * @author Yuan
 * @version 1.0.0
 */
@AutoConfigureMockMvc
class SecurityHeadersIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("回應帶 Referrer-Policy: strict-origin-when-cross-origin")
    void responsesCarryReferrerPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/assets?page=0&size=1"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }
}
