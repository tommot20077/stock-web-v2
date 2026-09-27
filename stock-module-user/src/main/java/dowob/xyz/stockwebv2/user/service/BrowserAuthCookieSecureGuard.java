package dowob.xyz.stockwebv2.user.service;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 啟動守衛:開發 / 測試以外的 profile,auth cookie 必須 {@code secure}。
 *
 * <p>比照 {@code JwtService} 對金鑰的守衛。{@code secure=false} 的 session cookie 會經明文 HTTP 送出,
 * 設錯時寧可拒絕啟動,也不要帶著它上線(2026-09-02 安全審查 L-6)。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class BrowserAuthCookieSecureGuard {

    private static final Profiles LOCAL_PROFILES = Profiles.of("dev", "test", "e2e", "e2e-browser");

    /**
     * @param properties  cookie 設定
     * @param environment Spring 環境
     * @throws IllegalStateException 非開發 / 測試 profile 且 {@code secure=false}
     */
    public BrowserAuthCookieSecureGuard(BrowserAuthCookieProperties properties, Environment environment) {
        if (!properties.secure() && !environment.acceptsProfiles(LOCAL_PROFILES)) {
            throw new IllegalStateException(
                "STOCK_AUTH_COOKIE_SECURE must be true outside dev/test/e2e/e2e-browser profiles");
        }
    }
}
