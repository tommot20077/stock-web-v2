package dowob.xyz.stockwebv2.user.api;

public record AuthResponse(String accessToken, String refreshToken, MeResponse user) {
}
