package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * access cookie TTL 必須等於 JWT access token TTL(D-22)。
 *
 * <p>比照 {@link BrowserAuthCookieSecureGuardTest}:設錯就拒絕啟動。例外訊息必須是靜態字串,
 * 只指名兩個環境變數,不回射實際設定值(T-05-11)。
 *
 * @author Yuan
 * @version 1.0
 */
class AccessTokenTtlConsistencyGuardTest {

    private static BrowserAuthCookieProperties cookie(Duration accessTokenTtl) {
        return new BrowserAuthCookieProperties(null, null, null, "Lax", false, null,
            accessTokenTtl, Duration.ofDays(14));
    }

    private static JwtProperties jwt(Duration accessTokenTtl) {
        return new JwtProperties(null, accessTokenTtl, Duration.ofDays(14));
    }

    @Test
    @DisplayName("cookie PT15M、JWT PT15M → 可啟動")
    void equalDefaults_areAccepted() {
        assertThatCode(() -> new AccessTokenTtlConsistencyGuard(
            cookie(Duration.ofMinutes(15)), jwt(Duration.ofMinutes(15))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cookie PT15M、JWT PT30M → 拒絕啟動,訊息只指名變數、不帶實際值")
    void mismatchedTtl_failsFastWithStaticMessage() {
        assertThatThrownBy(() -> new AccessTokenTtlConsistencyGuard(
            cookie(Duration.ofMinutes(15)), jwt(Duration.ofMinutes(30))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL")
            .hasMessageContaining("STOCK_JWT_ACCESS_TOKEN_TTL")
            .hasMessageNotContaining("PT30M")
            .hasMessageNotContaining("PT15M")
            .hasMessageNotContaining("30")
            .hasMessageNotContaining("15");
    }

    @Test
    @DisplayName("cookie TTL 未設定(正規化為 15 分鐘)、JWT PT15M → 可啟動")
    void nullCookieTtlNormalizedToFifteenMinutes_isAccepted() {
        assertThatCode(() -> new AccessTokenTtlConsistencyGuard(cookie(null), jwt(Duration.ofMinutes(15))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cookie PT2H、JWT PT2H(e2e-browser 形狀)→ 可啟動")
    void equalLongTtl_isAccepted() {
        assertThatCode(() -> new AccessTokenTtlConsistencyGuard(
            cookie(Duration.ofHours(2)), jwt(Duration.ofHours(2))))
            .doesNotThrowAnyException();
    }
}
