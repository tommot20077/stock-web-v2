package dowob.xyz.stockwebv2.user.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 瀏覽器 auth cookie 的環境設定。
 *
 * @param accessName access token cookie 名稱
 * @param refreshName refresh token cookie 名稱
 * @param path cookie path
 * @param sameSite SameSite policy
 * @param secure 是否只允許 HTTPS 傳送
 * @param domain cookie domain，空白代表不設定
 * @param accessTokenTtl access cookie 有效時間
 * @param refreshTokenTtl refresh cookie 有效時間
 * @author yuan
 * @version 1.0
 */
@ConfigurationProperties(prefix = "stock.auth.cookie")
public record BrowserAuthCookieProperties(
    String accessName,
    String refreshName,
    String path,
    String sameSite,
    boolean secure,
    String domain,
    Duration accessTokenTtl,
    Duration refreshTokenTtl
) {
    public BrowserAuthCookieProperties {
        accessName = StringUtils.defaultIfBlank(accessName, "stock_access");
        refreshName = StringUtils.defaultIfBlank(refreshName, "stock_refresh");
        path = StringUtils.defaultIfBlank(path, "/");
        sameSite = StringUtils.defaultIfBlank(sameSite, "Lax");
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
            refreshTokenTtl = Duration.ofDays(14);
        }
        if ("None".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalArgumentException("SameSite=None auth cookies require secure=true");
        }
    }
}
