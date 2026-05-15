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
import dowob.xyz.stockwebv2.user.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
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

    public AuthController(
        AuthService authService,
        JwtService jwtService,
        RefreshTokenService refreshTokenService,
        UserRepository userRepository
    ) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
    }

    @PostMapping("/auth/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        User user = authService.register(request);
        return ApiResponse.success(authResponse(user, servletRequest), meta());
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        User user = authService.verifyCredentials(request.email(), request.password());
        return ApiResponse.success(authResponse(user, servletRequest), meta());
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("user"));
        return ApiResponse.success(user.toMeResponse(), meta());
    }

    @PostMapping("/auth/logout")
    public ApiResponse<EmptyResponse> logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ApiResponse.empty(meta());
    }

    private AuthResponse authResponse(User user, HttpServletRequest servletRequest) {
        String accessToken = jwtService.createAccessToken(user.id(), user.role(), user.tokenVersion());
        String refreshToken = refreshTokenService.issue(user, servletRequest.getHeader("User-Agent"));
        return new AuthResponse(accessToken, refreshToken, user.toMeResponse());
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
