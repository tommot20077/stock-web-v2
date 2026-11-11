package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;

import org.springframework.stereotype.Component;

/**
 * 啟動守衛:access cookie 的有效時間必須等於 JWT access token 的有效時間(D-22)。
 *
 * <p>為什麼必須相等:
 * <ul>
 *   <li>JWT 比 cookie 長 —— cookie 在瀏覽器端過期後,外洩出去的 JWT 仍然有效,
 *       「外洩的 access cookie 最多有效 15 分鐘」的保證就破了(T-05-10)。</li>
 *   <li>JWT 比 cookie 短 —— cookie 還在瀏覽器裡,裡面的 JWT 已過期,前端每次都先吃一個 401
 *       才走 refresh,行為和契約描述不一致。</li>
 * </ul>
 *
 * <p>兩者預設皆為 {@code PT15M};分別由 {@code STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL} 與
 * {@code STOCK_JWT_ACCESS_TOKEN_TTL} 覆寫,必須成對設定。<strong>不設 profile 豁免</strong>:
 * dev / test / e2e / e2e-browser 也必須真的相等。比對用 {@link BrowserAuthCookieProperties#accessTokenTtl()}
 * 正規化後的值(未設定時為 15 分鐘)。
 *
 * <p>例外訊息是靜態字串,只指名兩個環境變數,不回射實際設定值(T-05-11)。
 * 契約見 {@code ai-docs/browser-auth-contract.md} 的 Auth Cookies 一節。
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class AccessTokenTtlConsistencyGuard {

    /**
     * @param cookie 瀏覽器 auth cookie 設定
     * @param jwt    JWT 簽發設定
     * @throws IllegalStateException access cookie TTL 與 JWT access token TTL 不相等
     */
    public AccessTokenTtlConsistencyGuard(BrowserAuthCookieProperties cookie, JwtProperties jwt) {
        if (!cookie.accessTokenTtl().equals(jwt.accessTokenTtl())) {
            throw new IllegalStateException(
                "STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL must equal STOCK_JWT_ACCESS_TOKEN_TTL");
        }
    }
}
