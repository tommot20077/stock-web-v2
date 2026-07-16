package dowob.xyz.stockwebv2.user.api;

/**
 * 非瀏覽器用戶端的 bearer token 回應。
 *
 * @param accessToken JWT access token
 * @param refreshToken opaque refresh token
 * @param user 使用者資料
 * @author yuan
 * @version 1.0
 */
public record TokenResponse(String accessToken, String refreshToken, MeResponse user) {
}
