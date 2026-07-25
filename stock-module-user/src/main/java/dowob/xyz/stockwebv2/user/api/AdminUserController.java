package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.user.service.AuthService;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
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

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 解除指定使用者的登入失敗鎖定（security.md §15）。
     *
     * @param uuid 使用者對外 UUID
     * @return 空回應
     */
    @PostMapping("/users/{uuid}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<EmptyResponse> unlock(@PathVariable UUID uuid) {
        authService.unlockByUuid(uuid);
        return ApiResponse.empty(meta());
    }

    /**
     * 建構回應中繼資料（trace id 與時間戳）。
     *
     * @return API 中繼資料
     */
    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(ObjectUtils.defaultIfNull(traceId, "missing-trace-id"), OffsetDateTime.now());
    }
}
