package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.trading.service.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TradingController {
    private final TradingService tradingService;
    private final AuditLogger auditLogger;

    public TradingController(TradingService tradingService, AuditLogger auditLogger) {
        this.tradingService = tradingService;
        this.auditLogger = auditLogger;
    }

    @PostMapping("/trades")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<TradeDto> createTrade(
        @Valid @RequestBody CreateTradeRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = authenticatedUserId(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            TradeDto trade = tradingService.createTrade(userId, request);
            auditLogger.log(userId, "trade_create", "trade:" + trade.id(), "success", ip);
            return ApiResponse.success(trade, meta());
        } catch (BusinessException exception) {
            auditLogger.log(userId, "trade_create", "trade", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }

    @GetMapping("/trades")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    public ApiResponse<PageResponse<TradeDto>> listTrades(
        @RequestParam(required = false) String symbol,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String dateFrom,
        @RequestParam(required = false) String dateTo,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String direction,
        @RequestParam(defaultValue = "0") String page,
        @RequestParam(defaultValue = "20") String size,
        Authentication authentication
    ) {
        return ApiResponse.success(tradingService.listTrades(
            authenticatedUserId(authentication),
            symbol,
            type,
            dateFrom,
            dateTo,
            sort,
            direction,
            parseQueryInt(page, "page"),
            parseQueryInt(size, "size")
        ), meta());
    }

    @GetMapping("/portfolio/holdings")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    public ApiResponse<List<HoldingDto>> holdings(Authentication authentication) {
        return ApiResponse.success(tradingService.listHoldings(authenticatedUserId(authentication)), meta());
    }

    @GetMapping("/portfolio/summary")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    public ApiResponse<PortfolioSummaryDto> summary(Authentication authentication) {
        return ApiResponse.success(tradingService.summary(authenticatedUserId(authentication)), meta());
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || StringUtils.isBlank(authentication.getName())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
    }

    private int parseQueryInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " must be a number");
        }
    }

    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(ObjectUtils.defaultIfNull(traceId, "missing-trace-id"), OffsetDateTime.now());
    }
}
