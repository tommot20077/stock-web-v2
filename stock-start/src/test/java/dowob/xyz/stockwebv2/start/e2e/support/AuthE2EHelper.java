package dowob.xyz.stockwebv2.start.e2e.support;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
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

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public AuthE2EHelper(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public AuthSession register(String email, String username, String password) throws Exception {
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
