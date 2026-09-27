package dowob.xyz.stockwebv2.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非開發 / 測試 profile 下 auth cookie 必須 secure(2026-09-02 安全審查 L-6)。
 *
 * <p>比照 JWT key 的守衛:設錯就拒絕啟動,而不是帶著明文可傳的 session cookie 上線。
 *
 * @author Yuan
 * @version 1.0.0
 */
class BrowserAuthCookieSecureGuardTest {

    private static BrowserAuthCookieProperties cookie(boolean secure) {
        return new BrowserAuthCookieProperties(null, null, null, "Lax", secure, null,
            Duration.ofMinutes(15), Duration.ofDays(14));
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("demo profile + secure=false → 拒絕啟動")
    void insecureCookieOutsideDevProfiles_failsFast() {
        assertThatThrownBy(() -> new BrowserAuthCookieSecureGuard(cookie(false), env("demo")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STOCK_AUTH_COOKIE_SECURE");
    }

    @Test
    @DisplayName("dev / test / e2e / e2e-browser 允許 secure=false(本機 HTTP)")
    void insecureCookieInDevProfiles_isAllowed() {
        for (String profile : new String[] {"dev", "test", "e2e", "e2e-browser"}) {
            assertThatCode(() -> new BrowserAuthCookieSecureGuard(cookie(false), env(profile)))
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("secure=true 在任何 profile 都可啟動")
    void secureCookie_isAlwaysAllowed() {
        assertThatCode(() -> new BrowserAuthCookieSecureGuard(cookie(true), env("demo")))
            .doesNotThrowAnyException();
    }
}
