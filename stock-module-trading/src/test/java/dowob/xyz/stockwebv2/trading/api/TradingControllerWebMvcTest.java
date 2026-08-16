package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.trading.service.TradingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TradingController} 的 WebMvc 切片測試，只驗證 {@code Idempotency-Key} 的<strong>綁定行為</strong>。
 *
 * <p><strong>能力界線</strong>：{@code GlobalExceptionHandler} 位於 {@code stock-start} 模組，
 * 不在本切片範圍內。因此這裡能證明的只有「缺 header 時 Spring 的參數綁定會失敗並產生 400」，
 * <strong>不能</strong>證明回應信封長什麼樣子（{@code error.code}、{@code error.fields}）。
 * 「缺 header → 400 envelope」的權威驗收在 {@code stock-start} 的
 * {@code ErrorHandlingIT} 與 {@code TradingApiIT}。</p>
 *
 * <p>安全設定以 {@link OpenSecurityConfig} 替換：本切片的目的不是驗證授權，
 * 若沿用預設鏈，所有請求都會先被 401 擋掉，反而看不到參數綁定的結果。</p>
 *
 * @author Yuan
 * @version 1.0.0
 */
@WebMvcTest(TradingController.class)
@Import(TradingControllerWebMvcTest.OpenSecurityConfig.class)
@DisplayName("Idempotency-Key header 綁定")
class TradingControllerWebMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TradingService tradingService;

    @MockitoBean
    AuditLogger auditLogger;

    /**
     * 合法的交易 body；{@code executedAt} 明確送出（D-03），避免後端補 {@code now()} 造成
     * 同一把 key 的重試 payload 每次都不同。
     */
    private static final String VALID_BODY = """
        {
          "symbol": "AAPL",
          "type": "BUY",
          "quantity": 10,
          "price": 150.00,
          "fee": 1.00,
          "note": "slice test",
          "executedAt": "2026-01-10T00:00:00Z"
        }
        """;

    @Test
    @DisplayName("缺少 Idempotency-Key header → 參數綁定失敗，回 400 而不是進入業務邏輯")
    void missingIdempotencyKeyHeaderIsRejectedAtBinding() throws Exception {
        mvc.perform(post("/api/v1/trades")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("帶上 Idempotency-Key header → 綁定成功並進入業務邏輯（證明 400 確實來自缺 header）")
    void requestWithIdempotencyKeyHeaderReachesService() throws Exception {
        /*
         * 這條是上一條的對照組。少了它，「回 400」也可能是 body 格式、security、
         * 路徑錯誤造成的，缺 header 的因果關係就沒被證明。
         */
        when(tradingService.createTrade(eq(42L), any(), eq("key-1"))).thenReturn(new TradeDto(
            "11111111-1111-1111-1111-111111111111",
            "AAPL",
            "BUY",
            BigDecimal.TEN,
            new BigDecimal("150.00"),
            BigDecimal.ONE,
            "slice test",
            OffsetDateTime.parse("2026-01-10T00:00:00Z"),
            OffsetDateTime.parse("2026-01-10T00:00:01Z")
        ));

        mvc.perform(post("/api/v1/trades")
                .with(user("42"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());
    }

    /**
     * 對所有請求放行的測試用安全設定，取代切片預設的「一律要求驗證」鏈。
     * CSRF 一併關閉：本切片測的是參數綁定，CSRF 的真實行為由 stock-start 的
     * {@code BrowserAuthFlowIT} 覆蓋。
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
}
