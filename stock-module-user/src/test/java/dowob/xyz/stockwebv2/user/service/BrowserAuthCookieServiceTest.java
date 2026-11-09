package dowob.xyz.stockwebv2.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrowserAuthCookieService} 寫出的 {@code Set-Cookie} 屬性單元測試。
 *
 * <p>Phase 5 SC1 稽核缺口 G1(D-01:只補缺口):既有整合測試只驗證 cookie 存在與 HttpOnly,
 * 未鎖住 {@code Max-Age} / {@code SameSite} / {@code Path} / {@code Secure} / {@code Domain}
 * 從 {@link BrowserAuthCookieProperties} 到 header 的對應。屬性規格見
 * {@code ai-docs/browser-auth-contract.md} 的 Auth Cookies 節;access cookie 的 TTL 必須等於
 * JWT access TTL(PT15M,由 {@code AccessTokenTtlConsistencyGuard} 守住),因此這裡斷言
 * {@code Max-Age=900}。
 *
 * <p>XSRF cookie 刻意<strong>不是</strong> HttpOnly:double-submit 需要前端 JS 讀值放進
 * {@code X-XSRF-TOKEN} header;auth cookie 則必須 HttpOnly,JS 不可讀。
 *
 * @author Yuan
 * @version 1.0
 */
class BrowserAuthCookieServiceTest {

    /** 15 分鐘 access TTL 的秒數,對應契約的 PT15M。 */
    private static final long ACCESS_MAX_AGE_SECONDS = 900L;

    /** 14 天 refresh TTL 的秒數。 */
    private static final long REFRESH_MAX_AGE_SECONDS = 1_209_600L;

    /** 32 bytes 以 base64url(無 padding)編碼後的長度。 */
    private static final int CSRF_TOKEN_LENGTH = 43;

    private static BrowserAuthCookieProperties properties(boolean secure, String domain) {
        return new BrowserAuthCookieProperties(null, null, null, "Lax", secure, domain,
            Duration.ofMinutes(15), Duration.ofDays(14));
    }

    private static List<String> setCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE);
    }

    private static String cookieStartingWith(List<String> headers, String prefix) {
        return headers.stream()
            .filter(header -> header.startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new AssertionError("找不到 Set-Cookie: " + prefix + ",實際:" + headers));
    }

    @Test
    @DisplayName("addAuthCookies(secure=false、無 domain)→ access / refresh 皆 HttpOnly、SameSite=Lax、Path=/,Max-Age 依 TTL")
    void addAuthCookiesWritesHttpOnlyLaxCookiesWithTtlMaxAge() {
        BrowserAuthCookieService service = new BrowserAuthCookieService(properties(false, null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.addAuthCookies(response, "a", "r");

        List<String> headers = setCookies(response);
        assertThat(headers).hasSize(2);

        String access = cookieStartingWith(headers, "stock_access=a;");
        assertThat(access)
            .contains("Max-Age=" + ACCESS_MAX_AGE_SECONDS)
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .contains("Path=/")
            .doesNotContain("Secure")
            .doesNotContain("Domain=");

        String refresh = cookieStartingWith(headers, "stock_refresh=r;");
        assertThat(refresh)
            .contains("Max-Age=" + REFRESH_MAX_AGE_SECONDS)
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .contains("Path=/")
            .doesNotContain("Secure")
            .doesNotContain("Domain=");
    }

    @Test
    @DisplayName("secure=true、domain 前後有空白 → 輸出 Secure 與 trim 後的 Domain")
    void secureAndDomainPropertiesAreReflectedInSetCookie() {
        BrowserAuthCookieService service = new BrowserAuthCookieService(properties(true, " example.com "));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.addAuthCookies(response, "a", "r");

        List<String> headers = setCookies(response);
        assertThat(headers).hasSize(2);
        assertThat(headers).allSatisfy(header -> assertThat(header)
            .contains("Secure")
            .contains("Domain=example.com")
            .doesNotContain("Domain= example.com")
            .contains("HttpOnly")
            .contains("SameSite=Lax"));
    }

    @Test
    @DisplayName("addCsrfCookie → XSRF-TOKEN 為 43 字元 base64url、Max-Age=900、不是 HttpOnly")
    void addCsrfCookieIsReadableByScriptAndUsesAccessTtl() {
        BrowserAuthCookieService service = new BrowserAuthCookieService(properties(false, null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.addCsrfCookie(response);

        List<String> headers = setCookies(response);
        assertThat(headers).hasSize(1);
        String csrf = cookieStartingWith(headers, BrowserAuthCookieService.XSRF_COOKIE + "=");
        String value = csrf.substring((BrowserAuthCookieService.XSRF_COOKIE + "=").length(), csrf.indexOf(';'));
        assertThat(value)
            .hasSize(CSRF_TOKEN_LENGTH)
            .matches("[A-Za-z0-9_-]+");
        assertThat(csrf)
            .contains("Max-Age=" + ACCESS_MAX_AGE_SECONDS)
            .contains("SameSite=Lax")
            .contains("Path=/")
            .doesNotContain("HttpOnly");
    }

    @Test
    @DisplayName("clearAuthCookies → access / refresh 兩條 Max-Age=0、值為空")
    void clearAuthCookiesExpiresBothCookies() {
        BrowserAuthCookieService service = new BrowserAuthCookieService(properties(false, null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clearAuthCookies(response);

        List<String> headers = setCookies(response);
        assertThat(headers).hasSize(2);
        assertThat(cookieStartingWith(headers, "stock_access=;"))
            .contains("Max-Age=0")
            .contains("HttpOnly")
            .contains("Path=/");
        assertThat(cookieStartingWith(headers, "stock_refresh=;"))
            .contains("Max-Age=0")
            .contains("HttpOnly")
            .contains("Path=/");
    }
}
