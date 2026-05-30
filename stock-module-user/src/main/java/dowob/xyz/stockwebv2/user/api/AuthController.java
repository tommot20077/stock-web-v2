package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.ResourceNotFoundException;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService;
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

    public AuthController(
        AuthService authService,
        JwtService jwtService,
        RefreshTokenService refreshTokenService,
        UserRepository userRepository,
        BrowserAuthCookieService cookieService
    ) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.cookieService = cookieService;
    }

    @PostMapping("/auth/register")
    public ApiResponse<BrowserSessionResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        User user = authService.register(request);
        return ApiResponse.success(browserSession(user, servletRequest, servletResponse), meta());
    }

    @PostMapping("/auth/login")
    public ApiResponse<BrowserSessionResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        User user = authService.verifyCredentials(request.email(), request.password());
        return ApiResponse.success(browserSession(user, servletRequest, servletResponse), meta());
    }

    @PostMapping("/auth/token")
    public ApiResponse<TokenResponse> token(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        User user = authService.verifyCredentials(request.email(), request.password());
        return ApiResponse.success(tokenResponse(user, servletRequest), meta());
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<BrowserSessionResponse> refresh(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String refreshToken = cookieService.readRefreshCookie(servletRequest);
        try {
            RefreshTokenService.RefreshSession session = refreshTokenService.consumeForRotation(refreshToken);
            User user = userRepository.findById(session.userId())
                .orElseThrow(() -> new ResourceNotFoundException("user"));
            return ApiResponse.success(browserSession(user, servletRequest, servletResponse), meta());
        } catch (BusinessException exception) {
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
        String browserRefreshToken = cookieService.readRefreshCookie(servletRequest);
        if (browserRefreshToken != null) {
            refreshTokenService.revoke(browserRefreshToken);
            cookieService.clearAuthCookies(servletResponse);
            return ApiResponse.empty(meta());
        }

        Long userId = authenticatedUserId(authentication);
        String refreshToken = request == null ? null : request.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage());
        }
        refreshTokenService.revoke(refreshToken, userId);
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

    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
