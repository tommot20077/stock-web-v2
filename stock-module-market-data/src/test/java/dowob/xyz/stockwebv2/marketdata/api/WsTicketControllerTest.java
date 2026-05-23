package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.marketdata.ws.WsTicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WsTicketController} 的 WebMvc 切片測試。
 *
 * <p>使用 {@link OpenSecurityConfig} 替換安全配置，讓所有請求通過，
 * 並以 {@code @WithMockUser} 注入模擬身份，驗證 controller 邏輯而非 filter chain。
 * 真正的 401（無 JWT）由 filter chain 保護，屬 E2E 層級（T28）驗證。
 *
 * @author Yuan
 * @version 1.0.0
 */
@WebMvcTest(WsTicketController.class)
@Import({WsTicketControllerTest.OpenSecurityConfig.class, WsTicketControllerTest.TestExceptionHandler.class})
class WsTicketControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WsTicketService ticketService;

    @MockitoBean
    StringRedisTemplate redisTemplate;

    // ── 200 with valid auth ───────────────────────────────────────────────────

    @Test
    @DisplayName("認證使用者 → 200 + ticket / expiresIn=30 / wsUrl 存在")
    @WithMockUser(username = "42")
    @SuppressWarnings("unchecked")
    void authenticated_returnsTicket() throws Exception {
        // stub Redis hash lookup — controller reads tokenVersion from user:auth:{userId}
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(eq("user:auth:42"))).thenReturn(Map.of("tokenVersion", "1", "status", "ACTIVE"));

        when(ticketService.issue(eq(42L), any())).thenReturn("test-ticket-xyz");

        mvc.perform(post("/api/v1/market/ws/ticket"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ticket").value("test-ticket-xyz"))
            .andExpect(jsonPath("$.data.expiresIn").value(30))
            .andExpect(jsonPath("$.data.wsUrl").exists());
    }

    // ── Redis 認證狀態缺失 → 503 AUTH_REDIS_UNAVAILABLE ─────────────────────

    @Test
    @DisplayName("Redis user:auth:{id} 整個 key 不存在 → 503 AUTH_REDIS_UNAVAILABLE,不簽發 ticket")
    @WithMockUser(username = "42")
    @SuppressWarnings("unchecked")
    void redisAuthStateMissing_returns503_andDoesNotIssueTicket() throws Exception {
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(eq("user:auth:42"))).thenReturn(Collections.emptyMap());

        mvc.perform(post("/api/v1/market/ws/ticket"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(ErrorCode.AUTH_REDIS_UNAVAILABLE.name()));

        verify(ticketService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("Redis hash 有但缺 tokenVersion 欄位 → 503 AUTH_REDIS_UNAVAILABLE,不簽發 ticket")
    @WithMockUser(username = "42")
    @SuppressWarnings("unchecked")
    void redisAuthStateMissingTokenVersion_returns503() throws Exception {
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(eq("user:auth:42"))).thenReturn(Map.of("status", "ACTIVE"));

        mvc.perform(post("/api/v1/market/ws/ticket"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value(ErrorCode.AUTH_REDIS_UNAVAILABLE.name()));

        verify(ticketService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("Redis tokenVersion 欄位非數字 → 503 AUTH_REDIS_UNAVAILABLE")
    @WithMockUser(username = "42")
    @SuppressWarnings("unchecked")
    void redisTokenVersionUnparseable_returns503() throws Exception {
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(eq("user:auth:42"))).thenReturn(Map.of("tokenVersion", "not-an-int"));

        mvc.perform(post("/api/v1/market/ws/ticket"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value(ErrorCode.AUTH_REDIS_UNAVAILABLE.name()));

        verify(ticketService, never()).issue(any(), any());
    }

    /**
     * 測試用最小安全設定：允許所有請求通過，不啟用任何驗證機制。
     * 僅在測試上下文生效，不影響生產設定。
     */
    @TestConfiguration
    static class OpenSecurityConfig {
        @Bean
        SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }
    }

    /**
     * 測試用例外處理器:將 {@link BusinessException} 轉換為對應 HTTP 狀態碼回應。
     * 取代生產環境的 {@code GlobalExceptionHandler}(位於 stock-start,不在本切片)。
     */
    @RestControllerAdvice
    static class TestExceptionHandler {
        @ExceptionHandler(BusinessException.class)
        ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
            ErrorCode code = ex.errorCode();
            ApiError error = ApiError.of(code, ex.getMessage());
            ApiMeta meta = new ApiMeta("test-trace", OffsetDateTime.now());
            return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, meta));
        }
    }
}
