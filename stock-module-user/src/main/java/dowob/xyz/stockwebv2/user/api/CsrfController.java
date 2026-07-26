package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.user.service.BrowserAuthCookieService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * 提供 SPA 可讀取的 CSRF bootstrap cookie。
 *
 * @author yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
public class CsrfController {
    private final BrowserAuthCookieService cookieService;

    public CsrfController(BrowserAuthCookieService cookieService) {
        this.cookieService = cookieService;
    }

    /**
     * 發出可讀取的 {@code XSRF-TOKEN} cookie，讓前端在 unsafe request 帶入 {@code X-XSRF-TOKEN}。
     *
     * @param response HTTP 回應
     * @return CSRF header/cookie 名稱
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfResponse> csrf(HttpServletResponse response) {
        cookieService.addCsrfCookie(response);
        return ApiResponse.success(new CsrfResponse("XSRF-TOKEN", "X-XSRF-TOKEN"), meta());
    }

    private ApiMeta meta() {
        return new ApiMeta(TraceIdFilter.currentTraceId(), OffsetDateTime.now());
    }

    public record CsrfResponse(String cookieName, String headerName) {
    }
}
