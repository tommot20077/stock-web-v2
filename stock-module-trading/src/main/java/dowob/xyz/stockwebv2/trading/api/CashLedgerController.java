package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.trading.service.CashLedgerService;

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
 * 冪等鍵與欄位驗證都在 service 層，避免驗證邏輯散落兩層。稽核事件由 04.1-22 補上。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
public class CashLedgerController {
    private final CashLedgerService cashLedgerService;

    public CashLedgerController(CashLedgerService cashLedgerService) {
        this.cashLedgerService = cashLedgerService;
    }

    /**
     * 記一筆入金或出金。這是「使用者自行記帳」的紀錄，不會驗證餘額、也不會真的移動資金。
     *
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層
     * @param authentication 已驗證的身分，name 即 userId
     * @return 建立完成的分錄；同一把鍵重送時為既有分錄
     * @throws BusinessException 冪等鍵非法、type 或 currency 不在白名單時
     */
    @PostMapping("/portfolio/cash-entries")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<CashEntryDto> createEntry(
        @Valid @RequestBody CreateCashEntryRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        return ApiResponse.success(cashLedgerService.createEntry(userId, request, idempotencyKey), ApiMetaFactory.current());
    }
}
