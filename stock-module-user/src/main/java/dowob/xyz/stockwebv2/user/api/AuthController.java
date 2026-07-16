package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.ResourceNotFoundException;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitService;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import dowob.xyz.stockwebv2.user.service.AuthService;
import dowob.xyz.stockwebv2.user.service.BrowserAuthCookieService;
import dowob.xyz.stockwebv2.user.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final BrowserAuthCookieService cookieService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final AuditLogger auditLogger;

    public AuthController(
        AuthService authService,
        JwtService jwtService,
        RefreshTokenService refreshTokenService,
        UserRepository userRepository,
        BrowserAuthCookieService cookieService,
        RateLimitService rateLimitService,
        RateLimitProperties rateLimitProperties,
        AuditLogger auditLogger
    ) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.cookieService = cookieService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.auditLogger = auditLogger;
    }

    @PostMapping("/auth/register")
    public ApiResponse<BrowserSessionResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        String ip = clientIp(servletRequest);
        rateLimitService.enforce("register", ip, rateLimitProperties.register());
        User user = authService.register(request);
        auditLogger.log(user.id(), "register", "user", "success", ip);
        return ApiResponse.success(browserSession(user, servletRequest, servletResponse), meta());
    }

    @PostMapping("/auth/login")
    public ApiResponse<BrowserSessionResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        String ip = clientIp(servletRequest);
        rateLimitService.enforce("login", ip, rateLimitProperties.login());
        User user = authenticate(request, ip);
        return ApiResponse.success(browserSession(user, servletRequest, servletResponse), meta());
    }

    @PostMapping("/auth/token")
    public ApiResponse<TokenResponse> token(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String ip = clientIp(servletRequest);
        rateLimitService.enforce("login", ip, rateLimitProperties.login());
        User user = authenticate(request, ip);
        return ApiResponse.success(tokenResponse(user, servletRequest), meta());
    }

    /**
     * 驗證登入憑證並記錄稽核事件（security.md §13）。成功與失敗皆須留下紀錄，
     * 失敗時不揭露目標帳號 id 以免稽核日誌成為帳號枚舉來源。
     *
     * @param request 登入請求
     * @param ip      來源 IP
     * @return 驗證通過的使用者
     */
    private User authenticate(LoginRequest request, String ip) {
        try {
            User user = authService.verifyCredentials(request.email(), request.password());
            auditLogger.log(user.id(), "login", "user", "success", ip);
            return user;
        } catch (BusinessException exception) {
            auditLogger.log(null, "login", "user", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<BrowserSessionResponse> refresh(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String refreshToken = cookieService.readRefreshCookie(servletRequest);
        Long refreshOwner = refreshTokenService.findOwner(refreshToken);
        String refreshIdentity = refreshOwner != null ? "u:" + refreshOwner : "ip:" + clientIp(servletRequest);
        rateLimitService.enforce("refresh", refreshIdentity, rateLimitProperties.refresh());
        try {
            RefreshTokenService.RefreshSession session = refreshTokenService.consumeForRotation(refreshToken);
            User user = userRepository.findById(session.userId())
                .orElseThrow(() -> new ResourceNotFoundException("user"));
            auditLogger.log(user.id(), "refresh", "session", "success", clientIp(servletRequest));
            return ApiResponse.success(browserSession(user, servletRequest, servletResponse), meta());
        } catch (BusinessException exception) {
            auditLogger.log(refreshOwner, "refresh", "session", "failure:" + exception.errorCode().name(),
                clientIp(servletRequest));
            cookieService.clearAuthCookies(servletResponse);
            throw exception;
        }
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user"));
        return ApiResponse.success(user.toMeResponse(), meta());
    }

    @PostMapping("/auth/logout")
    public ApiResponse<EmptyResponse> logout(
        @RequestBody(required = false) LogoutRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        String ip = clientIp(servletRequest);
        String browserRefreshToken = cookieService.readRefreshCookie(servletRequest);
        if (browserRefreshToken != null) {
            Long owner = refreshTokenService.findOwner(browserRefreshToken);
            refreshTokenService.revoke(browserRefreshToken);
            if (owner != null) {
                authService.logout(owner);
            }
            cookieService.clearAuthCookies(servletResponse);
            auditLogger.log(owner, "logout", "session", "success", ip);
            return ApiResponse.empty(meta());
        }

        Long userId = authenticatedUserId(authentication);
        String refreshToken = request == null ? null : request.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage());
        }
        refreshTokenService.revoke(refreshToken, userId);
        authService.logout(userId);
        auditLogger.log(userId, "logout", "session", "success", ip);
        return ApiResponse.empty(meta());
    }

    private BrowserSessionResponse browserSession(User user, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String accessToken = jwtService.createAccessToken(user.id(), user.role(), user.tokenVersion());
        String refreshToken = refreshTokenService.issue(user, servletRequest.getHeader("User-Agent"));
        cookieService.addAuthCookies(servletResponse, accessToken, refreshToken);
        OffsetDateTime now = OffsetDateTime.now();
        return new BrowserSessionResponse(
            user.toMeResponse(),
            now.plus(cookieService.accessTokenTtl()),
            now.plus(cookieService.refreshTokenTtl())
        );
    }

    private TokenResponse tokenResponse(User user, HttpServletRequest servletRequest) {
        String accessToken = jwtService.createAccessToken(user.id(), user.role(), user.tokenVersion());
        String refreshToken = refreshTokenService.issue(user, servletRequest.getHeader("User-Agent"));
        return new TokenResponse(accessToken, refreshToken, user.toMeResponse());
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
