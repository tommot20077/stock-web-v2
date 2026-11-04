package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.trading.service.ShareAdjustmentService;

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
import java.time.LocalDate;
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
 * {@link ShareAdjustmentController} 的 WebMvc 切片測試：{@code Idempotency-Key} 綁定、bean validation 範圍與稽核雙路。
 *
 * <p><strong>能力界線</strong>（同 {@code CashLedgerControllerWebMvcTest}）：{@code GlobalExceptionHandler} 位於
 * {@code stock-start}，不在本切片內，因此這裡只能證明「綁定 / 驗證失敗回 400 且 service 無互動」、
 * 「header 與 reversalOfId 原樣交給 service」與「稽核事件有輸出」；錯誤信封的 {@code error.code} /
 * {@code error.fields} 由 {@code ShareAdjustmentApiIT} 驗收。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(ShareAdjustmentController.class)
@Import(ShareAdjustmentControllerWebMvcTest.OpenSecurityConfig.class)
@DisplayName("股數調整端點：Idempotency-Key 綁定、範圍驗證與稽核")
class ShareAdjustmentControllerWebMvcTest {

    private static final String PATH = "/api/v1/portfolio/share-adjustments";
    private static final String CANARY_SYMBOL = "CANARYSYM";
    private static final String CANARY_KEY = "LEAK-CANARY-KEY";
    private static final String VALID_BODY = """
        {"symbol":"AAPL","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"2026-10-29"}
        """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ShareAdjustmentService shareAdjustmentService;

    @MockitoBean
    AuditLogger auditLogger;

    @Test
    @DisplayName("缺少 Idempotency-Key header → 400，service 與稽核皆無互動")
    void missingIdempotencyKeyHeaderIsRejectedAtBinding() throws Exception {
        mvc.perform(post(PATH)
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(shareAdjustmentService, auditLogger);
    }

    @Test
    @DisplayName("ratioNew / ratioOld 為 0 或 1001 → 400（bean validation），service 與稽核皆無互動")
    void ratioOutOfRangeIsRejectedByBeanValidation() throws Exception {
        for (String ratios : new String[] {
            "\"ratioNew\":0,\"ratioOld\":1", "\"ratioNew\":1001,\"ratioOld\":1",
            "\"ratioNew\":4,\"ratioOld\":0", "\"ratioNew\":4,\"ratioOld\":1001"}) {
            assertBadRequestWithoutEcho("""
                {"symbol":"%s","type":"SPLIT",%s,"effectiveDate":"2026-10-29"}
                """.formatted(CANARY_SYMBOL, ratios));
        }
        verifyNoInteractions(shareAdjustmentService, auditLogger);
    }

    @Test
    @DisplayName("quantity 小數 9 位 → 400（bean validation），service 無互動")
    void quantityWithNineDecimalsIsRejected() throws Exception {
        assertBadRequestWithoutEcho("""
            {"symbol":"%s","type":"STOCK_DIVIDEND","quantity":1.123456789,"effectiveDate":"2026-10-29"}
            """.formatted(CANARY_SYMBOL));
        verifyNoInteractions(shareAdjustmentService, auditLogger);
    }

    @Test
    @DisplayName("effectiveDate 不是日期字串 → 400，service 無互動")
    void malformedEffectiveDateIsRejected() throws Exception {
        assertBadRequestWithoutEcho("""
            {"symbol":"%s","type":"STOCK_DIVIDEND","quantity":5,"effectiveDate":"yesterday"}
            """.formatted(CANARY_SYMBOL));
        verifyNoInteractions(shareAdjustmentService, auditLogger);
    }

    @Test
    @DisplayName("reversalOfId 超過 36 字 → 400（bean validation），service 無互動")
    void oversizedReversalOfIdIsRejected() throws Exception {
        assertBadRequestWithoutEcho("""
            {"symbol":"%s","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"2026-10-29",
             "reversalOfId":"%s"}
            """.formatted(CANARY_SYMBOL, "x".repeat(37)));
        verifyNoInteractions(shareAdjustmentService, auditLogger);
    }

    @Test
    @DisplayName("成功 → service 收到該 key；稽核 share_adjustment_create / success，target 為 share_adjustment:<id>")
    void successfulCreateIsAudited() throws Exception {
        when(shareAdjustmentService.create(eq(42L), any(), eq("key-1"))).thenReturn(dto(null));

        mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());

        verify(shareAdjustmentService).create(eq(42L), any(), eq("key-1"));
        verify(auditLogger).log(eq(42L), eq("share_adjustment_create"),
            eq("share_adjustment:11111111-1111-1111-1111-111111111111"), eq("success"), any());
    }

    @Test
    @DisplayName("帶 reversalOfId → service 收到該字串；稽核 action 為 share_adjustment_reverse（不記 create）")
    void reversalIsPassedToServiceAndAuditedAsReverse() throws Exception {
        String originalId = "22222222-2222-2222-2222-222222222222";
        when(shareAdjustmentService.create(eq(42L), any(), eq("key-r"))).thenReturn(dto(originalId));

        mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", "key-r")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"AAPL","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"2026-10-29",
                     "reversalOfId":"%s"}
                    """.formatted(originalId)))
            .andExpect(status().isOk());

        ArgumentCaptor<CreateShareAdjustmentRequest> captor = ArgumentCaptor.forClass(CreateShareAdjustmentRequest.class);
        verify(shareAdjustmentService).create(eq(42L), captor.capture(), eq("key-r"));
        assertThat(captor.getValue().reversalOfId()).isEqualTo(originalId);
        verify(auditLogger).log(eq(42L), eq("share_adjustment_reverse"),
            eq("share_adjustment:11111111-1111-1111-1111-111111111111"), eq("success"), any());
        verify(auditLogger, never()).log(any(), eq("share_adjustment_create"), any(), any(), any());
    }

    @Test
    @DisplayName("被業務規則拒絕 → 稽核 share_adjustment_create / failure:<CODE> 後例外重拋")
    void rejectedCreateIsAuditedWithFailureCodeAndRethrown() {
        when(shareAdjustmentService.create(eq(42L), any(), eq(CANARY_KEY))).thenThrow(new BusinessException(
            ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED,
            ErrorCode.SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", CANARY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)))
            .hasRootCauseInstanceOf(BusinessException.class)
            .rootCause()
            .hasMessageNotContaining(CANARY_KEY);

        verify(auditLogger).log(eq(42L), eq("share_adjustment_create"), eq("share_adjustment"),
            eq("failure:SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED"), any());
    }

    @Test
    @DisplayName("沖銷被拒絕 → 稽核 share_adjustment_reverse / failure:<CODE> 後例外重拋")
    void rejectedReversalIsAuditedAsReverseFailure() {
        when(shareAdjustmentService.create(eq(42L), any(), eq("key-r"))).thenThrow(new BusinessException(
            ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE, ErrorCode.SHARE_ADJUSTMENT_NOT_REVERSIBLE.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post(PATH)
                .with(user("42"))
                .header("Idempotency-Key", "key-r")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"AAPL","type":"SPLIT","ratioNew":4,"ratioOld":1,"effectiveDate":"2026-10-29",
                     "reversalOfId":"22222222-2222-2222-2222-222222222222"}
                    """)))
            .hasRootCauseInstanceOf(BusinessException.class);

        verify(auditLogger).log(eq(42L), eq("share_adjustment_reverse"), eq("share_adjustment"),
            eq("failure:SHARE_ADJUSTMENT_NOT_REVERSIBLE"), any());
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

    private static ShareAdjustmentDto dto(String reversalOfId) {
        return new ShareAdjustmentDto(
            "11111111-1111-1111-1111-111111111111",
            "AAPL",
            "USD",
            "SPLIT",
            new BigDecimal("300"),
            4,
            1,
            LocalDate.parse("2026-10-29"),
            OffsetDateTime.parse("2026-10-30T15:00:00Z"),
            null,
            reversalOfId,
            false
        );
    }

    /**
     * 對所有請求放行的測試用安全設定，取代切片預設的「一律要求驗證」鏈；CSRF 的真實行為由 {@code ShareAdjustmentApiIT} 覆蓋。
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
