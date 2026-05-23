package dowob.xyz.stockwebv2.start.e2e.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Component
@Profile("e2e")
public class AuthE2EHelper {

    @Nullable
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    /**
     * 建構子：MockMvc 為選填，允許在 RANDOM_PORT 上下文（WebSocket E2E 測試）中初始化此 Bean。
     *
     * <p>在 MockMvc-based 的上下文中，{@code mockMvc} 由 Spring 自動注入；
     * 在 RANDOM_PORT 上下文中，{@code mockMvc} 為 {@code null}，此 Bean 仍可正常建立，
     * 但呼叫 {@link #register} 或 {@link #login} 方法時將拋出 {@link IllegalStateException}。
     *
     * @param mockMvc      MockMvc 實例（可為 null）
     * @param objectMapper Jackson ObjectMapper
     */
    @Autowired
    public AuthE2EHelper(@Autowired(required = false) MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public AuthSession register(String email, String username, String password) throws Exception {
        if (mockMvc == null) {
            throw new IllegalStateException("AuthE2EHelper.register() 需要 MockMvc，但目前上下文（RANDOM_PORT）中不存在 MockMvc。");
        }
        String response = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "username", username,
                    "password", password
                ))))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andReturn()
            .getResponse()
            .getContentAsString();

        return readSession(response);
    }

    public AuthSession login(String email, String password) throws Exception {
        if (mockMvc == null) {
            throw new IllegalStateException("AuthE2EHelper.login() 需要 MockMvc，但目前上下文（RANDOM_PORT）中不存在 MockMvc。");
        }
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", password
                ))))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andReturn()
            .getResponse()
            .getContentAsString();

        return readSession(response);
    }

    public static RequestPostProcessor bearerToken(String accessToken) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + accessToken);
            return request;
        };
    }

    private AuthSession readSession(String responseBody) throws Exception {
        JsonNode data = objectMapper.readTree(responseBody).get("data");
        return new AuthSession(
            data.get("accessToken").asText(),
            data.get("refreshToken").asText(),
            data.get("user")
        );
    }

    public record AuthSession(String accessToken, String refreshToken, JsonNode user) {
    }
}
