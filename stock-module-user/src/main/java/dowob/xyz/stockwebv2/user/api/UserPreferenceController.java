package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;
import dowob.xyz.stockwebv2.user.service.UserPreferenceService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 使用者偏好端點（PORT-09）：修改基準幣。
 *
 * <p>controller 只解析身分、IP 與稽核，業務規則全在 {@link UserPreferenceService}。
 * cookie 認證的 PATCH 由既有 {@code BrowserCsrfFilter} 要求 {@code X-XSRF-TOKEN}，
 * bearer 路徑不需 CSRF。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
public class UserPreferenceController {
    private final UserPreferenceService userPreferenceService;
    private final AuditLogger auditLogger;

    /**
     * @param userPreferenceService 偏好設定服務
     * @param auditLogger           稽核記錄器
     */
    public UserPreferenceController(UserPreferenceService userPreferenceService, AuditLogger auditLogger) {
        this.userPreferenceService = userPreferenceService;
        this.auditLogger = auditLogger;
    }

    /**
     * 修改登入者的基準幣。PATCH 設定值天然冪等，故不要求 {@code Idempotency-Key}。
     *
     * @param request        請求本文，已通過 bean validation
     * @param authentication 已驗證的身分，userId 只取自此處
     * @param servletRequest 原始請求，用於解析客戶端 IP 寫入稽核
     * @return 更新後的 {@link MeResponse}
     * @throws BusinessException 幣別不支援（400 VALIDATION_FAILED）或使用者不存在時
     */
    @PatchMapping("/me/preferences")
    @PreAuthorize("hasAuthority('PROFILE_EDIT')")
    public ApiResponse<MeResponse> updatePreferences(
        @Valid @RequestBody UpdatePreferencesRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            MeResponse response = userPreferenceService.updateBaseCurrency(userId, request.baseCurrency());
            auditLogger.log(userId, "preferences_update", "user", "success", ip);
            return ApiResponse.success(response, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, "preferences_update", "user", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }
}
