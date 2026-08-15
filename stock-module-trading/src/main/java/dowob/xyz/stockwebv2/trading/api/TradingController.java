package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.trading.service.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 交易與投資組合的 HTTP 端點。
 *
 * <p>本 controller 只負責取出已驗證身分的 userId、把 query string 原始字串原樣往下傳，
 * 以及為交易建立留下稽核事件；所有參數的白名單驗證與型別解析都在
 * {@link TradingService} 完成，避免驗證邏輯散落在兩層。唯一的例外是 {@code page} /
 * {@code size}：它們宣告為 String 再自行轉數字，讓「非數字」也走統一的
 * {@code VALIDATION_FAILED} 錯誤信封，而不是 Spring 型別轉換的通用 400。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@RestController
@RequestMapping("/api/v1")
public class TradingController {
    private final TradingService tradingService;
    private final AuditLogger auditLogger;

    public TradingController(TradingService tradingService, AuditLogger auditLogger) {
        this.tradingService = tradingService;
        this.auditLogger = auditLogger;
    }

    /**
     * 建立一筆交易並更新持倉。
     *
     * <p>無論成功或被業務規則拒絕，都會輸出一筆稽核事件（security.md §13）；
     * 被拒時例外仍向外拋出，交由 {@code GlobalExceptionHandler} 轉成錯誤信封。</p>
     *
     * @param request        交易建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層，
     *                       controller 不做業務判斷
     * @param authentication 已驗證的身分，name 即 userId
     * @param servletRequest 原始請求，用於解析客戶端 IP 寫入稽核
     * @return 建立完成的交易
     * @throws BusinessException 標的不存在或不可交易、交易類型不支援、持倉不足、
     *                           成交時間為未來時間、冪等鍵非法或被重複使用，或併發衝突時
     */
    @PostMapping("/trades")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<TradeDto> createTrade(
        @Valid @RequestBody CreateTradeRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = authenticatedUserId(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            TradeDto trade = tradingService.createTrade(userId, request, idempotencyKey);
            auditLogger.log(userId, "trade_create", "trade:" + trade.id(), "success", ip);
            return ApiResponse.success(trade, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, "trade_create", "trade", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }

    /**
     * 查詢登入者的交易紀錄，支援標的、類型、成交時間區間篩選與白名單排序（D-05 / D-06 / D-07）。
     *
     * <p>所有篩選與排序參數皆為選填，語意與非法值行為以
     * {@link TradingService#listTrades} 為準；本方法不做任何解析，僅原樣轉交。</p>
     *
     * <p>日期參數請以 UTC 瞬間（{@code 2026-01-01T00:00:00Z}）傳送。若要帶
     * {@code +08:00} 這類偏移量，{@code '+'} <strong>必須</strong>百分比編碼成 {@code %2B}：
     * servlet 會把裸的 {@code '+'} 解成空白。服務層雖已還原此種破壞，客戶端仍應正確編碼。</p>
     *
     * @param symbol         標的代號；省略或空白代表不依標的篩選
     * @param type           交易類型 BUY / SELL，大小寫不敏感；省略或空白代表不篩選
     * @param dateFrom       成交時間下界（含），ISO-8601 日期或時間戳；省略代表不設下界
     * @param dateTo         成交時間上界（不含），ISO-8601 日期或時間戳；純日期形式涵蓋當日整天
     * @param sort           排序鍵 executedAt / createdAt / total / quantity；省略代表 executedAt
     * @param direction      排序方向 asc / desc；省略代表 desc
     * @param page           頁碼，預設 0，夾限 0..10000
     * @param size           每頁筆數，預設 20，夾限 1..100
     * @param authentication 已驗證的身分，name 即 userId
     * @return 該頁交易與符合篩選條件的總筆數
     * @throws BusinessException 標的不存在、類型不支援、排序參數非法、日期格式非法、
     *                           dateFrom 晚於 dateTo，或 page / size 非數字時
     */
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
        ), ApiMetaFactory.current());
    }

    @GetMapping("/portfolio/holdings")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    public ApiResponse<List<HoldingDto>> holdings(Authentication authentication) {
        return ApiResponse.success(tradingService.listHoldings(authenticatedUserId(authentication)), ApiMetaFactory.current());
    }

    @GetMapping("/portfolio/summary")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    public ApiResponse<PortfolioSummaryDto> summary(Authentication authentication) {
        return ApiResponse.success(tradingService.summary(authenticatedUserId(authentication)), ApiMetaFactory.current());
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
}
