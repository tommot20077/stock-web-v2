package dowob.xyz.stockwebv2.start.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 掛上一般 API 限流。
 *
 * <p>只套用在 {@code /api/**}；{@code /api/v1/auth/**} 排除，因為 login / register / refresh
 * 已由 {@code AuthController} 以各自的專屬門檻限流，重複計數會讓兩套門檻互相干擾。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Configuration
public class ApiRateLimitConfig implements WebMvcConfigurer {

    private final ApiRateLimitInterceptor interceptor;

    /**
     * @param interceptor 一般 API 限流攔截器
     */
    public ApiRateLimitConfig(ApiRateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/v1/auth/**");
    }
}
