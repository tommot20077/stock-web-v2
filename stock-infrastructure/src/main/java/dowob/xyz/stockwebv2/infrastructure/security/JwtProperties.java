package dowob.xyz.stockwebv2.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "stock.jwt")
public record JwtProperties(String privateKey, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public JwtProperties {
        requirePositive(accessTokenTtl, "accessTokenTtl");
        requirePositive(refreshTokenTtl, "refreshTokenTtl");
    }

    private static void requirePositive(Duration duration, String propertyName) {
        if (duration == null) {
            throw new IllegalArgumentException(propertyName + " must not be null");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
    }
}
