package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.trading.service.DividendService;

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
 * 現金股利的 HTTP 端點（DIV-01）。
 *
 * <p>本 controller 只負責取出已驗證身分的 userId 並轉交 {@link DividendService}；冪等鍵與欄位驗證都在 service 層。
 * 無論成功或被業務規則拒絕，都會輸出一筆 {@code dividend_create}（沖銷為 {@code dividend_reverse}）稽核事件
 * （security.md §13）；稽核只記 userId / action / 資源 id / 結果 / ip，不記金額與冪等鍵。
 * Bean Validation 400 與缺 header 在進入方法前就被擋下，不留稽核（同 cash-entries）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@RestController
@RequestMapping("/api/v1")
public class CashDividendController {

    /**
     * 現金股利寫入服務。
     */
    private final DividendService dividendService;

    /**
     * 稽核事件輸出。
     */
    private final AuditLogger auditLogger;

    /**
     * 建立現金股利端點。
     *
     * @param dividendService 現金股利寫入服務
     * @param auditLogger     稽核事件輸出
     */
    public CashDividendController(DividendService dividendService, AuditLogger auditLogger) {
        this.dividendService = dividendService;
        this.auditLogger = auditLogger;
    }

    /**
     * 記一筆現金股利。這是「使用者自行記帳」的紀錄，不動股數與平均成本，也不要求持有該標的；
     * body 帶 {@code reversalOfId} 時為沖銷，稽核事件記為 {@code dividend_reverse}。
     *
     * <p>被業務規則拒絕時稽核記 {@code failure:<code>} 後原例外照常重拋，交由 {@code GlobalExceptionHandler} 轉成錯誤信封。</p>
     *
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，原樣轉交 service；空白與長度檢查一律在 service 層
     * @param authentication 已驗證的身分，name 即 userId
     * @param servletRequest 原始請求，用於解析客戶端 IP 寫入稽核
     * @return 建立完成的紀錄；同一把鍵重送時為既有紀錄
     * @throws BusinessException 冪等鍵 / 欄位非法、入帳時間在未來、沖銷值不符（400）、標的或沖銷對象不存在（404）、
     *                           同鍵不同 payload、沖銷對象已沖銷或不可沖銷（409）時
     */
    @PostMapping("/portfolio/dividends")
    @PreAuthorize("hasAuthority('TRADE_EXECUTE')")
    public ApiResponse<DividendDto> createDividend(
        @Valid @RequestBody CreateDividendRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        // 沖銷與一般紀錄是不同的稽核事件：事後追查「誰沖銷了哪一筆」不必從 payload 反推。
        String action = request.reversalOfId() == null ? "dividend_create" : "dividend_reverse";
        try {
            DividendDto dividend = dividendService.createDividend(userId, request, idempotencyKey);
            auditLogger.log(userId, action, "dividend:" + dividend.id(), "success", ip);
            return ApiResponse.success(dividend, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, action, "dividend", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }
}
