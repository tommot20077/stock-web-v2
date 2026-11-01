package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.trading.service.ShareAdjustmentService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配股與拆股 / 合股的 HTTP 端點（ADJ-01）。
 *
 * <p>入口刻意與 {@code POST /trades} 分開（D-11）：股數調整沒有成交價，不是交易紀錄。
 * 本 controller 只負責取出已驗證身分的 userId 並轉交 {@link ShareAdjustmentService}；冪等鍵與欄位驗證都在 service 層。
 * 稽核事件（{@code share_adjustment_create} / {@code share_adjustment_reverse}）由 04.2-08 與沖銷流程一併加入。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
public class ShareAdjustmentController {

    /**
     * 股數調整寫入服務。
     */
    private final ShareAdjustmentService shareAdjustmentService;

    /**
     * 建立股數調整端點。
     *
     * @param shareAdjustmentService 股數調整寫入服務
     */
    public ShareAdjustmentController(ShareAdjustmentService shareAdjustmentService) {
        this.shareAdjustmentService = shareAdjustmentService;
    }

    /**
     * 記一筆配股或拆股 / 合股，並依除權日起點持股改寫目前持倉（股數變、總成本不變）。
     *
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層
     * @param authentication 已驗證的身分，name 即 userId
     * @return 建立完成的紀錄；同一把鍵重送時為既有紀錄
     * @throws BusinessException 冪等鍵 / 欄位非法、除權日超前或早於最晚調整（400）、標的不存在或不可交易（404）、
     *                           除權日起點或目前無持倉（409）時
     */
    @PostMapping("/portfolio/share-adjustments")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<ShareAdjustmentDto> createShareAdjustment(
        @Valid @RequestBody CreateShareAdjustmentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        return ApiResponse.success(
            shareAdjustmentService.create(userId, request, idempotencyKey), ApiMetaFactory.current());
    }
}
