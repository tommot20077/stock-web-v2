package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 方法層授權（{@code @PreAuthorize}）拒絕時的 HTTP 狀態碼整合測試（security.md §7）。
 *
 * <p>URL 層放行、但方法層拒絕的情境下，回應必須為 403，而非被 catch-all handler 吞成 500。
 * 本測試以測試專用端點驗證：該端點要求 {@code ASSET_ADMIN}（USER 角色未持有），
 * 且路徑不在 {@code /api/admin/**}（避免被 URL 層先攔），因此必然走到方法層拒絕路徑。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@Import(MethodSecurityDenialIT.MethodSecuredTestController.class)
@DisplayName("方法層授權拒絕的狀態碼")
class MethodSecurityDenialIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("USER 呼叫需 ASSET_ADMIN 的端點時方法層拒絕應回 403")
    void methodLevelDenialReturnsForbidden() throws Exception {
        String token = registerAndGetToken("method-denial@example.com", "methoddenial", "Password1");

        mockMvc.perform(get("/api/v1/test-only/asset-admin")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    private String registerAndGetToken(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("accessToken").asText();
    }

    /**
     * 測試專用端點：僅以方法層 {@code @PreAuthorize} 保護，用於驗證方法層拒絕的狀態碼。
     */
    @RestController
    static class MethodSecuredTestController {

        /**
         * 需要 {@code ASSET_ADMIN} 權限的測試端點（USER 角色未持有此權限）。
         *
         * @return 空回應（授權通過時才會到達）
         */
        @GetMapping("/api/v1/test-only/asset-admin")
        @PreAuthorize("hasAuthority('ASSET_ADMIN')")
        public ApiResponse<EmptyResponse> assetAdminOnly() {
            return ApiResponse.empty(null);
        }
    }
}
