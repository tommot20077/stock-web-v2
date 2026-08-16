package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.trading.service.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TradingController} 交易稽核覆蓋的單元測試（security.md §13）。
 *
 * <p>直接以 mock 協作者驗證 controller 方法邏輯：交易建立無論成功或被拒，
 * 皆須透過 {@link AuditLogger} 留下稽核事件，且被拒時例外仍向外拋出。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("交易稽核覆蓋")
class TradingControllerTest {

    /**
     * 刻意用可辨識的字串當 idempotency key：任何一處意外把它寫進 audit 或錯誤訊息，
     * 都能用 grep 在輸出中直接抓到，而不是只能靠斷言碰巧覆蓋到那個欄位。
     */
    private static final String IDEM_KEY_CANARY = "LEAK-CANARY-12345";

    private TradingService tradingService;
    private AuditLogger auditLogger;
    private TradingController controller;
    private Authentication authentication;
    private HttpServletRequest servletRequest;

    @BeforeEach
    void setup() {
        tradingService = mock(TradingService.class);
        auditLogger = mock(AuditLogger.class);
        controller = new TradingController(tradingService, auditLogger);
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("42");
        servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRemoteAddr()).thenReturn("10.0.0.1");
    }

    private CreateTradeRequest tradeRequest() {
        return new CreateTradeRequest("AAPL", "BUY", BigDecimal.ONE, BigDecimal.TEN, null, null, null);
    }

    /** 成功路徑的 service 回傳值；controller 會取 id 組稽核目標，故不可為 null。 */
    private TradeDto tradeDto() {
        return new TradeDto(
            "11111111-1111-1111-1111-111111111111",
            "AAPL", "BUY", BigDecimal.ONE, BigDecimal.TEN, null, null, null, null);
    }

    @Test
    @DisplayName("交易建立被拒（BusinessException）→ 輸出 failure 稽核事件且例外向外拋出")
    void rejectedTradeEmitsFailureAudit() {
        when(tradingService.createTrade(eq(42L), any(), eq("key-1")))
            .thenThrow(new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found: AAPL"));

        assertThatThrownBy(() -> controller.createTrade(tradeRequest(), "key-1", authentication, servletRequest))
            .isInstanceOf(BusinessException.class);

        verify(auditLogger).log(
            eq(42L), eq("trade_create"), eq("trade"),
            eq("failure:" + ErrorCode.ASSET_NOT_FOUND.name()), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("Idempotency-Key header 值原樣轉交 service，controller 不做任何加工")
    void idempotencyKeyIsForwardedVerbatim() {
        when(tradingService.createTrade(eq(42L), any(), any())).thenReturn(tradeDto());

        controller.createTrade(tradeRequest(), IDEM_KEY_CANARY, authentication, servletRequest);

        /*
         * 用 captor 而非 eq()：eq() 只證明「有一次呼叫符合預期」，captor 則能看見實際傳進去的
         * 字串，若 controller 哪天偷偷 trim / 正規化 / 補預設值，這裡會直接顯示差異。
         */
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(tradingService).createTrade(eq(42L), any(), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(IDEM_KEY_CANARY);
    }

    @Test
    @DisplayName("空白 Idempotency-Key 不在 controller 攔截，原樣下傳由 service 判斷（分層界線）")
    void blankIdempotencyKeyIsForwardedInsteadOfRejectedAtController() {
        when(tradingService.createTrade(eq(42L), any(), any())).thenReturn(tradeDto());

        controller.createTrade(tradeRequest(), "   ", authentication, servletRequest);

        /*
         * 空白 key 的 400 屬於 service 的業務判斷（04-03 已實作）。controller 若自行 isBlank
         * 就會出現兩份規則，兩層一旦不同步就會產生「controller 放行、service 拒絕」或反之的
         * 縫隙，因此這裡明確斷言「原樣送達 service」而不是「被擋下」。
         */
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(tradingService).createTrade(eq(42L), any(), keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("   ");
    }

    @Test
    @DisplayName("失敗稽核事件的任何參數都不含 idempotency key（T-04-03）")
    void failureAuditNeverCarriesIdempotencyKey() {
        when(tradingService.createTrade(eq(42L), any(), eq(IDEM_KEY_CANARY)))
            .thenThrow(new BusinessException(
                ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED,
                ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.defaultMessage()));

        assertThatThrownBy(() -> controller.createTrade(
            tradeRequest(), IDEM_KEY_CANARY, authentication, servletRequest))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED);

        /*
         * key 是使用者可控字串，寫進 audit 等同把它落地到另一條保存期更長的管道。
         * 這裡逐一檢查每個字串參數，而不是只比對預期值，因為未來新增參數時
         * eq(...) 形式的驗證不會失敗，而這條會。
         */
        ArgumentCaptor<String> targetCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).log(
            eq(42L), eq("trade_create"), targetCaptor.capture(), resultCaptor.capture(), ipCaptor.capture());
        assertThat(targetCaptor.getValue()).doesNotContain(IDEM_KEY_CANARY);
        assertThat(resultCaptor.getValue()).doesNotContain(IDEM_KEY_CANARY);
        assertThat(ipCaptor.getValue()).doesNotContain(IDEM_KEY_CANARY);
    }

    @Test
    @DisplayName("六個查詢參數原樣轉交 service，controller 層不做任何解析或正規化")
    void listTradesForwardsEveryQueryParameterUnchanged() {
        when(tradingService.listTrades(
            eq(42L), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(PageResponse.<TradeDto>of(List.of(), 3, 50, 0L));

        controller.listTrades(
            " aapl ", "buy", "2026-01-01", "2026-01-31", "createdAt", "asc", "3", "50", authentication);

        /*
         * 刻意逐一比對而非用 any()：所有白名單與格式驗證都在 service 層，controller 只要少
         * trim 一次或吞掉一個參數，驗證關卡就會看到與客戶端不同的輸入。
         */
        verify(tradingService).listTrades(
            42L, " aapl ", "buy", "2026-01-01", "2026-01-31", "createdAt", "asc", 3, 50);
    }

    @Test
    @DisplayName("省略的查詢參數以 null 轉交，代表「不篩選」而非空字串")
    void omittedQueryParametersArriveAsNull() {
        when(tradingService.listTrades(
            eq(42L), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(PageResponse.<TradeDto>of(List.of(), 0, 20, 0L));

        controller.listTrades(null, null, null, null, null, null, "0", "20", authentication);

        verify(tradingService).listTrades(42L, null, null, null, null, null, null, 0, 20);
    }

    @Test
    @DisplayName("非數字的 page / size 回 VALIDATION_FAILED，且不觸及 service")
    void nonNumericPagingIsRejectedBeforeService() {
        assertThatThrownBy(() -> controller.listTrades(
            null, null, null, null, null, null, "abc", "20", authentication))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> controller.listTrades(
            null, null, null, null, null, null, "0", "xyz", authentication))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(tradingService);
    }
}
