package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import dowob.xyz.stockwebv2.user.service.AuthService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ADMIN 專用的使用者管理端點。位於 {@code /api/admin/**}，同時受 URL 層 {@code hasRole('ADMIN')}
 * 與方法層 {@code @PreAuthorize} 雙重保護（security.md §1）。
 *
 * @author Yuan
 * @version 1.1
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final AuthService authService;
    private final AuditLogger auditLogger;

    public AdminUserController(AuthService authService, AuditLogger auditLogger) {
        this.authService = authService;
        this.auditLogger = auditLogger;
    }

    /**
     * 解除指定使用者的登入失敗鎖定（security.md §15）。
     *
     * <p>稽核記錄的是<strong>操作者</strong>（ADMIN 自己）的 userId，被解鎖者放在 target；
     * 解鎖是改變帳號可用性的管理操作，成功與失敗都要留下「誰在什麼時候對誰做了什麼」。
     *
     * @param uuid           使用者對外 UUID
     * @param authentication 當前請求身份
     * @param servletRequest 用於解析來源 IP
     * @return 空回應
     */
    @PostMapping("/users/{uuid}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<EmptyResponse> unlock(@PathVariable UUID uuid,
                                             Authentication authentication,
                                             HttpServletRequest servletRequest) {
        Long operatorId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            authService.unlockByUuid(uuid);
            auditLogger.log(operatorId, "admin_unlock", "user:" + uuid, "success", ip);
            return ApiResponse.empty(ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(operatorId, "admin_unlock", "user:" + uuid, "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }
}
