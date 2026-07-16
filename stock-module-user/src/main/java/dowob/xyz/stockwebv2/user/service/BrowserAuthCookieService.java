package dowob.xyz.stockwebv2.user.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 集中管理瀏覽器 auth 與 CSRF cookie 寫入、讀取與清除。
 *
 * @author yuan
 * @version 1.0
 */
@Service
public class BrowserAuthCookieService {
    public static final String XSRF_COOKIE = "XSRF-TOKEN";
    public static final String XSRF_HEADER = "X-XSRF-TOKEN";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BrowserAuthCookieProperties properties;

    public BrowserAuthCookieService(BrowserAuthCookieProperties properties) {
        this.properties = properties;
    }

    public void addAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        addCookie(response, properties.accessName(), accessToken, properties.accessTokenTtl(), true);
        addCookie(response, properties.refreshName(), refreshToken, properties.refreshTokenTtl(), true);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, properties.accessName(), "", Duration.ZERO, true);
        addCookie(response, properties.refreshName(), "", Duration.ZERO, true);
    }

    public void addCsrfCookie(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        addCookie(response, XSRF_COOKIE, token, properties.accessTokenTtl(), false);
    }

    public String readAccessCookie(HttpServletRequest request) {
        return readCookie(request, properties.accessName());
    }

    public String readRefreshCookie(HttpServletRequest request) {
        return readCookie(request, properties.refreshName());
    }

    public String readCsrfCookie(HttpServletRequest request) {
        return readCookie(request, XSRF_COOKIE);
    }

    public String accessCookieName() {
        return properties.accessName();
    }

    public String refreshCookieName() {
        return properties.refreshName();
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    private void addCookie(HttpServletResponse response, String name, String value, Duration maxAge, boolean httpOnly) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(properties.secure())
            .sameSite(properties.sameSite())
            .path(properties.path())
            .maxAge(maxAge);
        if (properties.domain() != null && !properties.domain().isBlank()) {
            builder.domain(properties.domain().trim());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
