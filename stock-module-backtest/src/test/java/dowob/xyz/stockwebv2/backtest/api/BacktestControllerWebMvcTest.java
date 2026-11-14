package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.backtest.service.BacktestService;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link BacktestController} 的 WebMvc 切片測試：{@code initialCapital} 的上限與位數約束
 * 在 HTTP 驗證層即生效（BE-CR-01 / T-05-37）。
 *
 * <p><strong>能力界線</strong>：{@code GlobalExceptionHandler} 位於 {@code stock-start} 模組，
 * 不在本切片範圍內。因此這裡能證明的只有「界外 {@code initialCapital} 回 400」與
 * 「{@link BacktestService} 零互動（引擎沒有機會執行 {@code setScale}）」，<strong>不能</strong>證明
 * 回應信封長什麼樣子（{@code error.code}、{@code error.fields}）；信封的權威驗收在
 * {@code ValidationBoundaryE2E} 的 D3b 案例（需 Docker，由 CI E2E job 執行）。</p>
 *
 * <p>安全設定以 {@link OpenSecurityConfig} 替換：本切片的目的不是驗證授權，
 * 若沿用預設鏈，所有請求都會先被 401 擋掉，反而看不到 Bean Validation 的結果。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(BacktestController.class)
@Import(BacktestControllerWebMvcTest.OpenSecurityConfig.class)
@DisplayName("建立回測端點：initialCapital 上限與位數驗證")
class BacktestControllerWebMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BacktestService backtestService;

    @MockitoBean
    AuditLogger auditLogger;

    @Test
    @Timeout(10)
    @DisplayName("initialCapital 為指數字串 \"1e999999999\" → 400 且 service 零互動（回歸時以逾時失敗呈現，不會卡死）")
    void exponentialInitialCapitalIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/v1/backtests/runs")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("1e999999999")))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(backtestService);
    }

    @ParameterizedTest(name = "initialCapital = {0} → 400")
    @ValueSource(strings = {"123456789012.12345678", "1000000000000.000001"})
    @DisplayName("initialCapital 超過 6 位小數或超過 1e12 → 400 且 service 零互動")
    void outOfRangeInitialCapitalIsRejectedBeforeService(String capital) throws Exception {
        mvc.perform(post("/api/v1/backtests/runs")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(capital)))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(backtestService);
    }

    @Test
    @DisplayName("合法 initialCapital \"100000\" → service 收到請求一次且回 200（證明 400 確實來自 initialCapital）")
    void validInitialCapitalReachesService() throws Exception {
        when(backtestService.createRun(eq(42L), any())).thenReturn(runDto());

        mvc.perform(post("/api/v1/backtests/runs")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("100000")))
            .andExpect(status().isOk());

        verify(backtestService).createRun(eq(42L), any());
    }

    /**
     * 以字串送出 {@code initialCapital}（D-07 金額一律走 JSON 字串的正式路徑），其餘欄位為合法值。
     */
    private static String body(String initialCapital) {
        return """
            {"strategyId":"ma_cross","strategyCode":null,"symbol":"AAPL","period":"1Y","initialCapital":"%s","currency":"USD","benchmark":"buy_hold","dataMode":"cached"}
            """.formatted(initialCapital);
    }

    private static BacktestRunDto runDto() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-14T09:00:00+08:00");
        return new BacktestRunDto(
            "bt_11111111-1111-1111-1111-111111111111",
            "ma_cross",
            "MA Cross",
            "AAPL",
            "1Y",
            new BigDecimal("100000"),
            "USD",
            "succeeded",
            BigDecimal.ONE,
            now,
            now,
            now,
            null
        );
    }

    /**
     * 對所有請求放行的測試用安全設定，取代切片預設的「一律要求驗證」鏈；本切片不驗授權。
     * CSRF 一併關閉：本切片測的是 Bean Validation，CSRF 的真實行為由 stock-start 的整合測試覆蓋。
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
