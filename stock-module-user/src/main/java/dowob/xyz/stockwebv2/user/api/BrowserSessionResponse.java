package dowob.xyz.stockwebv2.user.api;

import java.time.OffsetDateTime;

/**
 * 瀏覽器工作階段回應，只暴露使用者與工作階段中繼資料，不回傳任何 token 值。
 *
 * @param user 使用者資料
 * @param accessTokenExpiresAt access cookie 對應的 access token 到期時間
 * @param refreshTokenExpiresAt refresh cookie 的絕對到期時間
 * @author yuan
 * @version 1.0
 */
public record BrowserSessionResponse(
    MeResponse user,
    OffsetDateTime accessTokenExpiresAt,
    OffsetDateTime refreshTokenExpiresAt
) {
}
