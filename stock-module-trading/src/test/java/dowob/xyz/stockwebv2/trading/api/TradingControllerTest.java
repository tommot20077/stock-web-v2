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
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;

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

    @Test
    @DisplayName("交易建立被拒（BusinessException）→ 輸出 failure 稽核事件且例外向外拋出")
    void rejectedTradeEmitsFailureAudit() {
        when(tradingService.createTrade(eq(42L), any()))
            .thenThrow(new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found: AAPL"));

        assertThatThrownBy(() -> controller.createTrade(tradeRequest(), authentication, servletRequest))
            .isInstanceOf(BusinessException.class);

        verify(auditLogger).log(
            eq(42L), eq("trade_create"), eq("trade"),
            eq("failure:" + ErrorCode.ASSET_NOT_FOUND.name()), eq("10.0.0.1"));
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
