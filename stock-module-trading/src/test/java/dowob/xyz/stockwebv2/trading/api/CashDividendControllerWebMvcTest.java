package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.trading.service.DividendService;

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
 * {@link CashDividendController} 的 WebMvc 切片測試：{@code Idempotency-Key} 綁定、bean validation 範圍與稽核雙路。
 *
 * <p><strong>能力界線</strong>（同 {@code ShareAdjustmentControllerWebMvcTest}）：{@code GlobalExceptionHandler} 位於
 * {@code stock-start}，不在本切片內，因此這裡只能證明「綁定 / 驗證失敗回 400、service 與稽核無互動、回應不回射輸入」、
 * 「header 與 reversalOfId 原樣交給 service」與「稽核事件有輸出、業務例外原樣重拋」。錯誤信封的
 * {@code error.fields} 鍵歸屬由 {@code CreateDividendRequestValidationTest}（property path）與
 * {@code DividendApiIT#missingIdempotencyKeyIsRejectedWithFieldError}（{@code fields['Idempotency-Key']}）驗收。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(CashDividendController.class)
@Import(CashDividendControllerWebMvcTest.OpenSecurityConfig.class)
@DisplayName("現金股利端點：Idempotency-Key 綁定、範圍驗證與稽核")
class CashDividendControllerWebMvcTest {

    private static final String PATH = "/api/v1/portfolio/dividends";
    private static final String CANARY_SYMBOL = "CANARYSYM";
    private static final String CANARY_KEY = "LEAK-CANARY-KEY";
    private static final String DTO_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ORIGINAL_ID = "22222222-2222-2222-2222-222222222222";
    private static final String VALID_BODY = """
        {"symbol":"AAPL","amount":120,"withholdingTax":30}
        """;
    private static final String REVERSAL_BODY = """
        {"symbol":"AAPL","amount":120,"withholdingTax":30,"reversalOfId":"%s"}
        """.formatted(ORIGINAL_ID);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DividendService dividendService;

    @MockitoBean
    AuditLogger auditLogger;

    @Test
    @DisplayName("缺少 Idempotency-Key header → 400，service 與稽核皆無互動")
    void missingIdempotencyKeyHeaderIsRejectedAtBinding() throws Exception {
        String response = mvc.perform(post(PATH)
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"%s","amount":120}
                    """.formatted(CANARY_SYMBOL)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response).doesNotContain(CANARY_SYMBOL);
        verifyNoInteractions(dividendService, auditLogger);
    }

    @Test
    @DisplayName("amount = 0、withholdingTax = -1、amount 小數 9 位、note 501 字、reversalOfId 37 字 → 400，service 與稽核皆無互動、不回射輸入")
    void outOfRangeFieldsAreRejectedByBeanValidation() throws Exception {
        for (String fields : new String[] {
            "\"amount\":0",
            "\"amount\":120,\"withholdingTax\":-1",
            "\"amount\":1.123456789",
            "\"amount\":120,\"note\":\"" + "n".repeat(501) + "\"",
            "\"amount\":120,\"reversalOfId\":\"" + "x".repeat(37) + "\""}) {
            assertBadRequestWithoutEcho("""
                {"symbol":"%s",%s}
                """.formatted(CANARY_SYMBOL, fields));
        }
        verifyNoInteractions(dividendService, auditLogger);
    }

    @Test
    @DisplayName("paidAt 不是 ISO-8601 → 400，service 無互動")
    void malformedPaidAtIsRejected() throws Exception {
        assertBadRequestWithoutEcho("""
            {"symbol":"%s","amount":120,"paidAt":"yesterday"}
            """.formatted(CANARY_SYMBOL));
        verifyNoInteractions(dividendService, auditLogger);
    }

    @Test
    @DisplayName("成功 → service 收到該 key；稽核 dividend_create / success，target 為 dividend:<id>")
    void successfulCreateIsAudited() throws Exception {
        when(dividendService.createDividend(eq(42L), any(), eq("key-1"))).thenReturn(dto(null));

        mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());

        verify(dividendService).createDividend(eq(42L), any(), eq("key-1"));
        verify(auditLogger).log(eq(42L), eq("dividend_create"), eq("dividend:" + DTO_ID), eq("success"), any());
        verify(auditLogger, never()).log(any(), eq("dividend_reverse"), any(), any(), any());
    }

    @Test
    @DisplayName("帶 reversalOfId → service 收到該字串；稽核 action 為 dividend_reverse（不記 create）")
    void reversalIsPassedToServiceAndAuditedAsReverse() throws Exception {
        when(dividendService.createDividend(eq(42L), any(), eq("key-r"))).thenReturn(dto(ORIGINAL_ID));

        mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", "key-r")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REVERSAL_BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<CreateDividendRequest> captor = ArgumentCaptor.forClass(CreateDividendRequest.class);
        verify(dividendService).createDividend(eq(42L), captor.capture(), eq("key-r"));
        assertThat(captor.getValue().reversalOfId()).isEqualTo(ORIGINAL_ID);
        verify(auditLogger).log(eq(42L), eq("dividend_reverse"), eq("dividend:" + DTO_ID), eq("success"), any());
        verify(auditLogger, never()).log(any(), eq("dividend_create"), any(), any(), any());
    }

    @Test
    @DisplayName("沖銷被拒（DIVIDEND_ALREADY_REVERSED）→ 稽核 dividend_reverse / failure:DIVIDEND_ALREADY_REVERSED 後例外重拋")
    void rejectedReversalIsAuditedWithFailureCodeAndRethrown() {
        when(dividendService.createDividend(eq(42L), any(), eq("key-r"))).thenThrow(new BusinessException(
            ErrorCode.DIVIDEND_ALREADY_REVERSED, ErrorCode.DIVIDEND_ALREADY_REVERSED.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", "key-r")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REVERSAL_BODY)))
            .hasRootCauseInstanceOf(BusinessException.class)
            .rootCause()
            .hasMessageNotContaining(ORIGINAL_ID);

        verify(auditLogger).log(eq(42L), eq("dividend_reverse"), eq("dividend"),
            eq("failure:DIVIDEND_ALREADY_REVERSED"), any());
    }

    @Test
    @DisplayName("同 key 不同 payload 被拒 → 稽核 dividend_create / failure:DIVIDEND_IDEMPOTENCY_KEY_REUSED；例外不含 key 與 symbol")
    void rejectedCreateIsAuditedWithoutEchoingInput() {
        when(dividendService.createDividend(eq(42L), any(), eq(CANARY_KEY))).thenThrow(new BusinessException(
            ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED, ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", CANARY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"%s","amount":120}
                    """.formatted(CANARY_SYMBOL))))
            .hasRootCauseInstanceOf(BusinessException.class)
            .rootCause()
            .hasMessageNotContaining(CANARY_KEY)
            .hasMessageNotContaining(CANARY_SYMBOL);

        verify(auditLogger).log(eq(42L), eq("dividend_create"), eq("dividend"),
            eq("failure:DIVIDEND_IDEMPOTENCY_KEY_REUSED"), any());
    }

    private void assertBadRequestWithoutEcho(String body) throws Exception {
        String response = mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", CANARY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(response).doesNotContain(CANARY_SYMBOL).doesNotContain(CANARY_KEY);
    }

    private static DividendDto dto(String reversalOfId) {
        OffsetDateTime paidAt = OffsetDateTime.parse("2026-10-30T01:15:00Z");
        return new DividendDto(DTO_ID, "AAPL", "USD", new BigDecimal("120"), new BigDecimal("30"), paidAt, paidAt,
            null, reversalOfId, false);
    }

    /**
     * 對所有請求放行的測試用安全設定，取代切片預設的「一律要求驗證」鏈；CSRF 的真實行為由 {@code DividendApiIT} 覆蓋。
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
