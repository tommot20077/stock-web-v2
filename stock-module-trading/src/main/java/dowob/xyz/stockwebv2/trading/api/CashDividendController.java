package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.trading.service.DividendService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 現金股利的 HTTP 端點（DIV-01）。
 *
 * <p>本 controller 只負責取出已驗證身分的 userId 並轉交 {@link DividendService}；冪等鍵與欄位驗證都在 service 層。
 * 稽核事件（{@code dividend_create} / {@code dividend_reverse}）由 04.2-04 與沖銷流程一併加入。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
public class CashDividendController {

    /**
     * 現金股利寫入服務。
     */
    private final DividendService dividendService;

    /**
     * 建立現金股利端點。
     *
     * @param dividendService 現金股利寫入服務
     */
    public CashDividendController(DividendService dividendService) {
        this.dividendService = dividendService;
    }

    /**
     * 記一筆現金股利。這是「使用者自行記帳」的紀錄，不動股數與平均成本，也不要求持有該標的。
     *
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層
     * @param authentication 已驗證的身分，name 即 userId
     * @return 建立完成的紀錄；同一把鍵重送時為既有紀錄
     * @throws BusinessException 冪等鍵非法、入帳時間在未來、標的不存在或不可交易時
     */
    @PostMapping("/portfolio/dividends")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<DividendDto> createDividend(
        @Valid @RequestBody CreateDividendRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        return ApiResponse.success(
            dividendService.createDividend(userId, request, idempotencyKey), ApiMetaFactory.current());
    }
}
