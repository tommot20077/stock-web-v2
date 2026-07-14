package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.common.error.ResourceNotFoundException;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import dowob.xyz.stockwebv2.user.service.LoginAttemptService;
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
 * @version 1.0
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;

    public AdminUserController(UserRepository userRepository, LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.loginAttemptService = loginAttemptService;
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
        User user = userRepository.findByUuid(uuid)
            .orElseThrow(() -> new ResourceNotFoundException("user"));
        loginAttemptService.reset(user.id());
        return ApiResponse.empty(meta());
    }

    /**
     * 建構回應中繼資料（trace id 與時間戳）。
     *
     * @return API 中繼資料
     */
    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
