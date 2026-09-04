package dowob.xyz.stockwebv2.marketdata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.marketdata.batch.BackfillJobLauncher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link BackfillController} 的 WebMvc 切片測試。
 *
 * <p>使用 {@link AdminOnlySecurityConfig} 替換安全設定，對 ADMIN 角色開放所有請求；
 * {@link BackfillJobLauncher}、{@link BackfillIdempotencyService} 與 {@link JobExplorer}
 * 以 {@code @MockitoBean} 替換，專注驗證 controller 邏輯。
 * {@link TestExceptionHandler} 處理 {@link BusinessException}，
 * 因 GlobalExceptionHandler 位於 stock-start，不在本切片範圍。
 *
 * @author Yuan
 * @version 1.0.0
 */
@WebMvcTest(BackfillController.class)
@Import({
    BackfillControllerTest.AdminOnlySecurityConfig.class,
    BackfillControllerTest.TestExceptionHandler.class
})
class BackfillControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BackfillJobLauncher launcher;

    @MockitoBean
    BackfillIdempotencyService idempotencyService;

    @MockitoBean
    JobExplorer jobExplorer;

    /** BackfillController 現在會寫稽核日誌；切片測試不驗證日誌內容，只需讓 context 起得來。 */
    @MockitoBean
    AuditLogger auditLogger;

    static final String BASE_URL = "/api/v1/market/backfill";
    static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant TO   = Instant.parse("2026-01-15T00:00:00Z");  // 14 天，在 90 天內

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private String buildRequest(String symbol, Instant from, Instant to, String interval) throws Exception {
        BackfillTriggerRequest req = new BackfillTriggerRequest(symbol, from, to, interval);
        return objectMapper().writeValueAsString(req);
    }

    // ── POST /api/v1/market/backfill ─────────────────────────────────────────

    @Test
    @DisplayName("POST /backfill: USER 角色 → 403 AUTH_FORBIDDEN")
    @WithMockUser(username = "77", roles = "USER")
    void trigger_userRole_returns403() throws Exception {
        mvc.perform(post(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequest("AAPL", FROM, TO, "1m")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("POST /backfill: ADMIN + 有效請求 → 202 + jobExecutionId")
    @WithMockUser(username = "77", roles = "ADMIN")
    void trigger_admin_validRequest_returns202WithJobId() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getId()).thenReturn(42L);
        when(execution.getStatus()).thenReturn(BatchStatus.STARTED);
        when(launcher.launch(any(), any(), any(), any())).thenReturn(execution);
        when(idempotencyService.tryAcquire(any())).thenReturn(true);

        mvc.perform(post(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequest("AAPL", FROM, TO, "1m"))
                .header("Idempotency-Key", "test-key-123"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobExecutionId").value(42))
            .andExpect(jsonPath("$.data.status").value("STARTED"));
    }

    @Test
    @DisplayName("POST /backfill: 範圍超過 90 天 → 400 BACKFILL_RANGE_TOO_LARGE")
    @WithMockUser(username = "77", roles = "ADMIN")
    void trigger_rangeOver90Days_returns400BackfillRangeTooLarge() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-04-15T00:00:00Z");  // 104 天，超過 90 天

        mvc.perform(post(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequest("AAPL", from, to, "1m")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("BACKFILL_RANGE_TOO_LARGE"));
    }

    @Test
    @DisplayName("POST /backfill: interval 無效 → 400 KLINE_INTERVAL_INVALID")
    @WithMockUser(username = "77", roles = "ADMIN")
    void trigger_invalidInterval_returns400KlineIntervalInvalid() throws Exception {
        mvc.perform(post(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequest("AAPL", FROM, TO, "INVALID")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("KLINE_INTERVAL_INVALID"));
    }

    @Test
    @DisplayName("POST /backfill: 相同 Idempotency-Key → 409 BACKFILL_ALREADY_RUNNING")
    @WithMockUser(username = "77", roles = "ADMIN")
    void trigger_sameIdempotencyKey_returns409BackfillAlreadyRunning() throws Exception {
        when(idempotencyService.tryAcquire("dup-key")).thenReturn(false);

        mvc.perform(post(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildRequest("AAPL", FROM, TO, "1m"))
                .header("Idempotency-Key", "dup-key"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("BACKFILL_ALREADY_RUNNING"));
    }

    // ── GET /api/v1/market/backfill/{jobExecutionId} ──────────────────────────

    @Test
    @DisplayName("GET /backfill/{jobExecutionId}: 不存在的 ID → 404 BACKFILL_JOB_NOT_FOUND")
    @WithMockUser(username = "77", roles = "ADMIN")
    void status_unknownJobExecutionId_returns404BackfillJobNotFound() throws Exception {
        when(jobExplorer.getJobExecution(999L)).thenReturn(null);

        mvc.perform(get(BASE_URL + "/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("BACKFILL_JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /backfill/{jobExecutionId}: ADMIN + 存在的 ID → 200 + 執行詳情")
    @WithMockUser(username = "77", roles = "ADMIN")
    void status_admin_returnsExecutionDetails() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getId()).thenReturn(42L);
        when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(execution.getStartTime()).thenReturn(
            java.time.LocalDateTime.parse("2026-01-01T00:00:00"));
        when(execution.getEndTime()).thenReturn(
            java.time.LocalDateTime.parse("2026-01-01T00:05:00"));
        when(execution.getStepExecutions()).thenReturn(java.util.List.of());
        when(execution.getAllFailureExceptions()).thenReturn(java.util.List.of());
        when(jobExplorer.getJobExecution(42L)).thenReturn(execution);

        mvc.perform(get(BASE_URL + "/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobExecutionId").value(42))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.failureMessage").isEmpty());
    }

    /**
     * 測試用安全設定：ADMIN 可通過，其餘角色由 requireAdmin() 邏輯攔截。
     * 關閉 CSRF，允許所有請求進入 controller（由 controller 自行檢查 ADMIN 角色）。
     */
    @TestConfiguration
    static class AdminOnlySecurityConfig {
        @Bean
        SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }
    }

    /**
     * 測試用例外處理器：將 {@link BusinessException} 轉換為對應 HTTP 狀態碼回應。
     * 取代生產環境的 GlobalExceptionHandler（位於 stock-start，不在本切片）。
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
