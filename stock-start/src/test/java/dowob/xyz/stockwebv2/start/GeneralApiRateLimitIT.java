package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;

/**
 * 一般 API 限流（security.md §15 General API Rate Limiting）。
 *
 * <p>原本只有 login / register / refresh 三個認證端點有限流；{@code POST /api/v1/trades}、
 * {@code POST /api/v1/backtests/runs}、公開的 {@code GET /api/v1/assets} 都沒有（安全審查 M-1）。
 * 規格是已登入端點每位使用者每分鐘 100 次、公開端點每個 IP 每分鐘 60 次；本測試把門檻調小驗證行為。
 *
 * <p>與 {@code AuthRateLimitAndLockoutIT} 同理，需要開啟限流（{@link ContainerIT} 預設關閉），
 * 故不繼承 {@link ContainerIT}，改在自身的 {@code @DynamicPropertySource} 共用容器 wiring。
 *
 * @author Yuan
 * @version 1.0.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("一般 API 限流")
class GeneralApiRateLimitIT {

    private static final int USER_LIMIT = 3;
    private static final int PUBLIC_LIMIT = 2;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContainerIT.registerContainerProperties(registry);
        registry.add("stock.security.rate-limit.enabled", () -> true);
        registry.add("stock.security.rate-limit.register.limit", () -> 1000);
        registry.add("stock.security.rate-limit.login.limit", () -> 1000);
        registry.add("stock.security.rate-limit.api.limit", () -> USER_LIMIT);
        registry.add("stock.security.rate-limit.api.window", () -> "PT1M");
        registry.add("stock.security.rate-limit.public-api.limit", () -> PUBLIC_LIMIT);
        registry.add("stock.security.rate-limit.public-api.window", () -> "PT1M");
    }

    @Test
    @DisplayName("已登入端點超過每位使用者門檻後回 429 並帶 Retry-After")
    void authenticatedEndpointIsLimitedPerUser() throws Exception {
        String token = registerAndToken("rl-api@example.com", "rlapi");

        for (int i = 0; i < USER_LIMIT; i++) {
            mockMvc.perform(get("/api/v1/portfolio/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/portfolio/summary").header("Authorization", "Bearer " + token))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_RATE_LIMITED")))
            .andExpect(header().string("Retry-After", notNullValue()));
    }

    @Test
    @DisplayName("每位使用者各自計數：一人用完額度不影響另一人")
    void usersHaveIndependentBuckets() throws Exception {
        String first = registerAndToken("rl-first@example.com", "rlfirst");
        String second = registerAndToken("rl-second@example.com", "rlsecond");

        for (int i = 0; i <= USER_LIMIT; i++) {
            mockMvc.perform(get("/api/v1/portfolio/summary").header("Authorization", "Bearer " + first));
        }

        mockMvc.perform(get("/api/v1/portfolio/summary").header("Authorization", "Bearer " + second))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("公開端點超過每 IP 門檻後回 429")
    void publicEndpointIsLimitedPerIp() throws Exception {
        RequestPostProcessor ip = fromIp("10.30.0.1");

        for (int i = 0; i < PUBLIC_LIMIT; i++) {
            mockMvc.perform(get("/api/v1/assets").param("query", "AAPL").with(ip))
                .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/assets").param("query", "AAPL").with(ip))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_RATE_LIMITED")));
    }

    @Test
    @DisplayName("認證端點不算進一般 API 額度，維持自己的專屬門檻")
    void authEndpointsAreNotCountedAgainstGeneralLimit() throws Exception {
        RequestPostProcessor ip = fromIp("10.30.0.2");

        for (int i = 0; i < PUBLIC_LIMIT + 2; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@example.com\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized());
        }
    }

    private String registerAndToken(String email, String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(fromIp("10.30.1.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"username\":\"%s\",\"password\":\"Password1\"}".formatted(email, username)))
            .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/v1/auth/token").with(fromIp("10.30.1.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Password1\"}".formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
