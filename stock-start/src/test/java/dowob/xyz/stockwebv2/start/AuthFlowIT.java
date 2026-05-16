package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthFlowIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void registerLoginMeLogoutFlowWorks() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"yuan@example.com","username":"yuan","password":"Password1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.accessToken", notNullValue()))
            .andExpect(jsonPath("$.data.refreshToken", notNullValue()));

        AuthTokens tokens = login("yuan@example.com", "Password1");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email", equalTo("yuan@example.com")));

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("{\"refreshToken\":\"" + tokens.refreshToken() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }

    @Test
    void meRejectsMalformedBearerTokenWithApiResponse() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }

    @Test
    void logoutRejectsBlankRefreshTokenWithValidationEnvelope() throws Exception {
        AuthTokens tokens = register("blank-refresh@example.com", "blankrefresh", "Password1");

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .content("{\"refreshToken\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_FAILED")));
    }

    @Test
    void logoutRequiresAuthentication() throws Exception {
        AuthTokens tokens = register("logout-required@example.com", "logoutrequired", "Password1");

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + tokens.refreshToken() + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }

    @Test
    void logoutRejectsRefreshTokenOwnedByAnotherUser() throws Exception {
        AuthTokens owner = register("refresh-owner@example.com", "refreshowner", "Password1");
        AuthTokens attacker = register("refresh-attacker@example.com", "refreshattacker", "Password1");

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + attacker.accessToken())
                .content("{\"refreshToken\":\"" + owner.refreshToken() + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_FORBIDDEN")));
    }

    private AuthTokens register(String email, String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return readTokens(body);
    }

    private AuthTokens login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
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
