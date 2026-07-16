package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link MarketController} klines 端點的 WebMvc 切片測試。
 *
 * <p>使用 {@link OpenSecurityConfig} 替換安全設定，讓所有請求通過；
 * {@link KlineQueryService} 以 {@code @MockitoBean} 替換；
 * {@link TestExceptionHandler} 處理 {@link BusinessException}，
 * 因 GlobalExceptionHandler 位於 stock-start，不在本切片範圍。
 *
 * @author Yuan
 * @version 1.0.0
 */
@WebMvcTest(MarketController.class)
@Import({
    MarketControllerKlinesTest.OpenSecurityConfig.class,
    MarketControllerKlinesTest.TestExceptionHandler.class
})
class MarketControllerKlinesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MarketLatestService latestService;

    @MockitoBean
    KlineQueryService klineQueryService;

    static final Instant FROM_TIME = Instant.parse("2026-01-01T10:00:00Z");
    static final Instant TO_TIME   = Instant.parse("2026-01-01T11:00:00Z");

    // ── GET /{symbol}/klines ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{symbol}/klines: interval 無效 → 400 KLINE_INTERVAL_INVALID")
    void klines_invalidInterval_returns400KlineIntervalInvalid() throws Exception {
        mvc.perform(get("/api/v1/market/AAPL/klines")
                .param("interval", "INVALID")
                .param("from", "2026-01-01T10:00:00Z")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("KLINE_INTERVAL_INVALID"));
    }

    @Test
    @DisplayName("GET /{symbol}/klines: 有效請求 → 200 + KlineDto list（BigDecimal 序列化為字串）")
    void klines_validRequest_returnsList() throws Exception {
        KlineDto dto = new KlineDto(
            FROM_TIME,
            new BigDecimal("100.00"),
            new BigDecimal("110.00"),
            new BigDecimal("99.00"),
            new BigDecimal("105.50"),
            new BigDecimal("2000.00")
        );
        when(klineQueryService.findKlines(
            eq("AAPL"),
            eq(KlineInterval.ONE_MINUTE),
            any(Instant.class),
            any(),
            any())
        ).thenReturn(List.of(dto));

        mvc.perform(get("/api/v1/market/AAPL/klines")
                .param("interval", "1m")
                .param("from", "2026-01-01T10:00:00Z")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].bucket").exists())
            .andExpect(jsonPath("$.data[0].open").value("100.00"))
            .andExpect(jsonPath("$.data[0].high").value("110.00"))
            .andExpect(jsonPath("$.data[0].low").value("99.00"))
            .andExpect(jsonPath("$.data[0].close").value("105.50"))
            .andExpect(jsonPath("$.data[0].volume").value("2000.00"))
            .andExpect(jsonPath("$.meta.traceId").exists());
    }

    @Test
    @DisplayName("GET /{symbol}/klines: 缺少 from 參數 → 400")
    void klines_missingFromParam_returns400() throws Exception {
        mvc.perform(get("/api/v1/market/AAPL/klines")
                .param("interval", "1m")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
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
