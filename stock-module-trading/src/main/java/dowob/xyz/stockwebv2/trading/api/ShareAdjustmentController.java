package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.trading.service.ShareAdjustmentService;

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
 * 配股與拆股 / 合股的 HTTP 端點（ADJ-01）。
 *
 * <p>入口刻意與 {@code POST /trades} 分開（D-11）：股數調整沒有成交價，不是交易紀錄。
 * 本 controller 只負責取出已驗證身分的 userId 並轉交 {@link ShareAdjustmentService}；冪等鍵與欄位驗證都在 service 層。
 * 無論成功或被業務規則拒絕，都會輸出一筆 {@code share_adjustment_create}（沖銷為 {@code share_adjustment_reverse}）
 * 稽核事件（security.md §13）；Bean Validation 400 與缺 header 在進入方法前就被擋下，不留稽核（同 cash-entries）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@RestController
@RequestMapping("/api/v1")
public class ShareAdjustmentController {

    /**
     * 股數調整寫入服務。
     */
    private final ShareAdjustmentService shareAdjustmentService;

    /**
     * 稽核事件輸出。
     */
    private final AuditLogger auditLogger;

    /**
     * 建立股數調整端點。
     *
     * @param shareAdjustmentService 股數調整寫入服務
     * @param auditLogger            稽核事件輸出
     */
    public ShareAdjustmentController(ShareAdjustmentService shareAdjustmentService, AuditLogger auditLogger) {
        this.shareAdjustmentService = shareAdjustmentService;
        this.auditLogger = auditLogger;
    }

    /**
     * 記一筆配股或拆股 / 合股，並依除權日起點持股改寫目前持倉（股數變、總成本不變）；
     * body 帶 {@code reversalOfId} 時為沖銷（撤銷原列已記錄的 ΔQ），稽核事件記為 {@code share_adjustment_reverse}。
     *
     * <p>被業務規則拒絕時稽核記 {@code failure:<code>} 後原例外照常重拋，交由 {@code GlobalExceptionHandler} 轉成錯誤信封。</p>
     *
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層
     * @param authentication 已驗證的身分，name 即 userId
     * @param servletRequest 原始請求，用於解析客戶端 IP 寫入稽核
     * @return 建立完成的紀錄；同一把鍵重送時為既有紀錄
     * @throws BusinessException 冪等鍵 / 欄位非法、除權日超前或早於最晚調整（400）、標的或沖銷對象不存在（404）、
     *                           無持倉、同鍵不同 payload、沖銷對象已沖銷或不可沖銷（409）時
     */
    @PostMapping("/portfolio/share-adjustments")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<ShareAdjustmentDto> createShareAdjustment(
        @Valid @RequestBody CreateShareAdjustmentRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        // 沖銷與一般紀錄是不同的稽核事件：事後追查「誰沖銷了哪一筆」不必從 payload 反推。
        String action = request.reversalOfId() == null ? "share_adjustment_create" : "share_adjustment_reverse";
        try {
            ShareAdjustmentDto adjustment = shareAdjustmentService.create(userId, request, idempotencyKey);
            auditLogger.log(userId, action, "share_adjustment:" + adjustment.id(), "success", ip);
            return ApiResponse.success(adjustment, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, action, "share_adjustment", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }
}
