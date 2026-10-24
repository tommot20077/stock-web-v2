package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.trading.service.CashLedgerService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 現金分錄（入金 / 出金）的 HTTP 端點（CASH-01）。
 *
 * <p>本 controller 只負責取出已驗證身分的 userId 並轉交 {@link CashLedgerService}；
 * 冪等鍵與欄位驗證都在 service 層，避免驗證邏輯散落兩層。
 * 無論成功或被業務規則拒絕，都會輸出一筆 {@code cash_entry_create} 稽核事件（security.md §13）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@RestController
@RequestMapping("/api/v1")
public class CashLedgerController {
    private final CashLedgerService cashLedgerService;

    private final AuditLogger auditLogger;

    public CashLedgerController(CashLedgerService cashLedgerService, AuditLogger auditLogger) {
        this.cashLedgerService = cashLedgerService;
        this.auditLogger = auditLogger;
    }

    /**
     * 記一筆入金或出金。這是「使用者自行記帳」的紀錄，不會驗證餘額、也不會真的移動資金。
     *
     * <p>被業務規則拒絕時稽核記 {@code failure:<code>} 後原例外照常重拋，交由
     * {@code GlobalExceptionHandler} 轉成錯誤信封。</p>
     *
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層
     * @param authentication 已驗證的身分，name 即 userId
     * @param servletRequest 原始請求，用於解析客戶端 IP 寫入稽核
     * @return 建立完成的分錄；同一把鍵重送時為既有分錄
     * @throws BusinessException 冪等鍵非法、type 或 currency 不在白名單、發生時間在未來，
     *                           或同鍵但 payload 不同時
     */
    @PostMapping("/portfolio/cash-entries")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<CashEntryDto> createEntry(
        @Valid @RequestBody CreateCashEntryRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            CashEntryDto entry = cashLedgerService.createEntry(userId, request, idempotencyKey);
            auditLogger.log(userId, "cash_entry_create", "cash_entry:" + entry.id(), "success", ip);
            return ApiResponse.success(entry, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, "cash_entry_create", "cash_entry", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }
}
