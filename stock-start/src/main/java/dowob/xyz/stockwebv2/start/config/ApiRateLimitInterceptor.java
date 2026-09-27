package dowob.xyz.stockwebv2.start.config;

import dowob.xyz.stockwebv2.infrastructure.security.RateLimitProperties;
import dowob.xyz.stockwebv2.infrastructure.security.RateLimitService;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 一般 API 限流（security.md §15 General API Rate Limiting）。
 *
 * <p>已登入的請求以使用者為鍵（{@link RateLimitProperties#api()}，預設 100/min），未登入的請求以來源 IP
 * 為鍵（{@link RateLimitProperties#publicApi()}，預設 60/min）。認證端點不經過這裡——它們有各自的專屬門檻，
 * 註冊路徑時排除。
 *
 * <p>用 {@link HandlerInterceptor} 而不是 servlet filter：這裡丟出的 {@code RateLimitExceededException}
 * 會交給全域例外處理，429 信封與 {@code Retry-After} 標頭與認證端點限流完全一致，不必再寫一份回應格式。
 * 執行時機在 Spring Security 過濾器鏈之後，{@link SecurityContextHolder} 已有認證結果。
 *
 * <p>來源 IP 經 {@link ClientIpResolver}，在 ingress 後方依 {@code server.forward-headers-strategy}
 * 取得原始客戶端 IP，不會把全站使用者算進同一個桶。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    static final String USER_BUCKET = "api";
    static final String PUBLIC_BUCKET = "public-api";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;

    /**
     * @param rateLimitService 以 Redis 計數的限流服務
     * @param properties       限流設定
     */
    public ApiRateLimitInterceptor(RateLimitService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (isAuthenticatedUser(authentication)) {
            rateLimitService.enforce(USER_BUCKET, "user:" + authentication.getName(), properties.api());
        } else {
            rateLimitService.enforce(PUBLIC_BUCKET, ClientIpResolver.resolve(request), properties.publicApi());
        }
        return true;
    }

    /**
     * @param authentication 目前的認證結果
     * @return 是否為已登入的真實使用者（排除 Spring Security 的匿名 token）
     */
    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
