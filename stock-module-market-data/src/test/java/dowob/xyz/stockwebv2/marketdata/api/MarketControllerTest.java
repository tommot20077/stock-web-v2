package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link MarketController} 的 WebMvc 切片測試。
 *
 * <p>使用 {@link OpenSecurityConfig} 替換安全設定，讓所有請求通過；
 * {@link MarketLatestService} 以 {@code @MockitoBean} 替換，專注驗證 controller 邏輯。
 * {@link TestExceptionHandler} 處理 {@link BusinessException}，
 * 因 {@code GlobalExceptionHandler} 位於 stock-start 模組，不在本切片範圍。
 *
 * @author Yuan
 * @version 1.0.0
 */
@WebMvcTest(MarketController.class)
@Import({MarketControllerTest.OpenSecurityConfig.class, MarketControllerTest.TestExceptionHandler.class})
class MarketControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MarketLatestService latestService;

    static final Instant FIXED_TIME = Instant.parse("2026-01-01T00:00:00Z");

    static LatestPriceDto aaplDto() {
        return new LatestPriceDto("AAPL", new BigDecimal("150.50"), new BigDecimal("1000.00"), FIXED_TIME);
    }

    // ── GET /{symbol}/latest ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{symbol}/latest: 有資料 → 200 + DTO（BigDecimal 序列化為字串）")
    void latest_returns200WithDto() throws Exception {
        when(latestService.findLatest("AAPL")).thenReturn(Optional.of(aaplDto()));

        mvc.perform(get("/api/v1/market/AAPL/latest").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.symbol").value("AAPL"))
            .andExpect(jsonPath("$.data.price").value("150.50"))
            .andExpect(jsonPath("$.data.volume").value("1000.00"))
            .andExpect(jsonPath("$.data.time").exists())
            .andExpect(jsonPath("$.meta.traceId").exists());
    }

    @Test
    @DisplayName("GET /{symbol}/latest: 未知 symbol (ASSET_NOT_FOUND) → 404")
    void latest_unknownSymbol_returns404() throws Exception {
        when(latestService.findLatest("UNKNOWN"))
            .thenThrow(new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found: UNKNOWN"));

        mvc.perform(get("/api/v1/market/UNKNOWN/latest").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ASSET_NOT_FOUND"));
    }

    // ── GET /latest?symbols=... ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /latest?symbols=...: 批次超過 50 → 400")
    void latestBatch_overSize_returns400() throws Exception {
        when(latestService.findLatestBatch(anyList()))
            .thenThrow(new BusinessException(ErrorCode.LATEST_BATCH_TOO_LARGE, "Batch size 51 exceeds limit 50"));

        // 產生 51 個 symbol 的 query string
        String symbols = String.join(",", Collections.nCopies(51, "AAPL"));

        mvc.perform(get("/api/v1/market/latest")
                .param("symbols", symbols)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("LATEST_BATCH_TOO_LARGE"));
    }

    @Test
    @DisplayName("GET /latest?symbols=AAPL,BTC: 正常批次 → 200 + list")
    void latestBatch_returns200WithList() throws Exception {
        LatestPriceDto btcDto = new LatestPriceDto("BTC", new BigDecimal("60000.00"), new BigDecimal("5.00"), FIXED_TIME);
        when(latestService.findLatestBatch(any())).thenReturn(List.of(aaplDto(), btcDto));

        mvc.perform(get("/api/v1/market/latest")
                .param("symbols", "AAPL,BTC")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].symbol").value("AAPL"))
            .andExpect(jsonPath("$.data[1].symbol").value("BTC"))
            .andExpect(jsonPath("$.meta.traceId").exists());
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
     * 取代生產環境的 {@code GlobalExceptionHandler}（位於 stock-start，不在本切片）。
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
