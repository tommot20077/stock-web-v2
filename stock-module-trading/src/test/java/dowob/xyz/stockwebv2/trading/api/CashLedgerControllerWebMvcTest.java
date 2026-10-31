package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.trading.service.CashLedgerService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CashLedgerController} 的 WebMvc 切片測試：{@code Idempotency-Key} 的<strong>綁定行為</strong>與稽核雙路。
 *
 * <p><strong>能力界線</strong>：{@code GlobalExceptionHandler} 位於 {@code stock-start} 模組，
 * 不在本切片範圍內。因此這裡能證明的只有「缺 header 時 Spring 的參數綁定會失敗並產生 400」、
 * 「header 值原樣交給 service」與「稽核事件有輸出」，<strong>不能</strong>證明回應信封長什麼樣子
 * （{@code error.code}、{@code error.fields}）；信封的權威驗收在 {@code CashEntryApiIT}。</p>
 *
 * <p>安全設定以 {@link OpenSecurityConfig} 替換：本切片的目的不是驗證授權，
 * 若沿用預設鏈，所有請求都會先被 401 擋掉，反而看不到參數綁定的結果。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(CashLedgerController.class)
@Import(CashLedgerControllerWebMvcTest.OpenSecurityConfig.class)
@DisplayName("入出金端點：Idempotency-Key 綁定與稽核")
class CashLedgerControllerWebMvcTest {

    private static final String VALID_BODY = """
        {"type":"DEPOSIT","currency":"TWD","amount":50000,"occurredAt":"2026-10-01T09:00:00+08:00"}
        """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CashLedgerService cashLedgerService;

    @MockitoBean
    AuditLogger auditLogger;

    @Test
    @DisplayName("缺少 Idempotency-Key header → 參數綁定失敗回 400，service 無任何互動")
    void missingIdempotencyKeyHeaderIsRejectedAtBinding() throws Exception {
        mvc.perform(post("/api/v1/portfolio/cash-entries")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(cashLedgerService);
    }

    @Test
    @DisplayName("帶上 Idempotency-Key header → service 收到該 key（證明 400 確實來自缺 header）")
    void requestWithIdempotencyKeyHeaderReachesServiceWithThatKey() throws Exception {
        when(cashLedgerService.createEntry(eq(42L), any(), eq("key-1"))).thenReturn(entryDto());

        mvc.perform(post("/api/v1/portfolio/cash-entries")
                .with(user("42"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());

        verify(cashLedgerService).createEntry(eq(42L), any(), eq("key-1"));
    }

    @Test
    @DisplayName("成功 → 稽核 cash_entry_create / success，target 為 cash_entry:<id>")
    void successfulCreateIsAudited() throws Exception {
        when(cashLedgerService.createEntry(eq(42L), any(), eq("key-1"))).thenReturn(entryDto());

        mvc.perform(post("/api/v1/portfolio/cash-entries")
                .with(user("42"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());

        verify(auditLogger).log(eq(42L), eq("cash_entry_create"),
            eq("cash_entry:11111111-1111-1111-1111-111111111111"), eq("success"), any());
    }

    @Test
    @DisplayName("被業務規則拒絕 → 稽核 failure:<code> 後例外照常重拋（交由 GlobalExceptionHandler）")
    void rejectedCreateIsAuditedWithFailureCodeAndRethrown() {
        when(cashLedgerService.createEntry(eq(42L), any(), eq("key-1"))).thenThrow(new BusinessException(
            ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED, ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post("/api/v1/portfolio/cash-entries")
                .with(user("42"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)))
            .hasRootCauseInstanceOf(BusinessException.class);

        verify(auditLogger).log(eq(42L), eq("cash_entry_create"), eq("cash_entry"),
            eq("failure:CASH_IDEMPOTENCY_KEY_REUSED"), any());
    }

    @Test
    @DisplayName("帶 reversalOfId 的請求：service 收到該字串，稽核 action 為 cash_entry_reverse（一般入出金仍為 cash_entry_create）")
    void reversalIsPassedToServiceAndAuditedAsReverse() throws Exception {
        String originalId = "22222222-2222-2222-2222-222222222222";
        when(cashLedgerService.createEntry(eq(42L), any(), eq("key-r"))).thenReturn(entryDto());

        mvc.perform(post("/api/v1/portfolio/cash-entries")
                .with(user("42"))
                .header("Idempotency-Key", "key-r")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"WITHDRAWAL","currency":"TWD","amount":50000,"reversalOfId":"%s"}
                    """.formatted(originalId)))
            .andExpect(status().isOk());

        ArgumentCaptor<CreateCashEntryRequest> captor = ArgumentCaptor.forClass(CreateCashEntryRequest.class);
        verify(cashLedgerService).createEntry(eq(42L), captor.capture(), eq("key-r"));
        assertThat(captor.getValue().reversalOfId()).isEqualTo(originalId);
        verify(auditLogger).log(eq(42L), eq("cash_entry_reverse"),
            eq("cash_entry:11111111-1111-1111-1111-111111111111"), eq("success"), any());
        verify(auditLogger, never()).log(any(), eq("cash_entry_create"), any(), any(), any());
    }

    @Test
    @DisplayName("沖銷被拒絕 → 稽核 cash_entry_reverse / failure:<code> 後例外重拋")
    void rejectedReversalIsAuditedAsReverseFailure() {
        when(cashLedgerService.createEntry(eq(42L), any(), eq("key-r"))).thenThrow(new BusinessException(
            ErrorCode.CASH_ENTRY_ALREADY_REVERSED, ErrorCode.CASH_ENTRY_ALREADY_REVERSED.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post("/api/v1/portfolio/cash-entries")
                .with(user("42"))
                .header("Idempotency-Key", "key-r")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"WITHDRAWAL","currency":"TWD","amount":50000,
                     "reversalOfId":"22222222-2222-2222-2222-222222222222"}
                    """)))
            .hasRootCauseInstanceOf(BusinessException.class);

        verify(auditLogger).log(eq(42L), eq("cash_entry_reverse"), eq("cash_entry"),
            eq("failure:CASH_ENTRY_ALREADY_REVERSED"), any());
    }

    private static CashEntryDto entryDto() {
        return new CashEntryDto(
            "11111111-1111-1111-1111-111111111111",
            "DEPOSIT",
            "TWD",
            new BigDecimal("50000"),
            null,
            OffsetDateTime.parse("2026-10-01T09:00:00+08:00"),
            OffsetDateTime.parse("2026-10-01T09:00:01+08:00"),
            null,
            null,
            false
        );
    }

    /**
     * 對所有請求放行的測試用安全設定，取代切片預設的「一律要求驗證」鏈。
     * CSRF 一併關閉：本切片測的是參數綁定，CSRF 的真實行為由 {@code CashEntryApiIT} 覆蓋。
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
