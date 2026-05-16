package dowob.xyz.stockwebv2.infrastructure.security;

import dowob.xyz.stockwebv2.common.model.Role;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @Test
    void createsAndParsesAccessTokenWithGeneratedDevelopmentKey() {
        JwtProperties properties = new JwtProperties("", Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties, environment("dev"));

        String token = jwtService.createAccessToken(42L, Role.USER, 7);
        JwtService.JwtClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo(Role.USER);
        assertThat(claims.tokenVersion()).isEqualTo(7);
    }

    @Test
    void createsAndParsesAccessTokenWithConfiguredP256PrivateKey() throws Exception {
        JwtProperties properties = new JwtProperties(privateKeyPem("secp256r1"), Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties, environment("prod"));

        String token = jwtService.createAccessToken(42L, Role.USER, 7);
        JwtService.JwtClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo(Role.USER);
        assertThat(claims.tokenVersion()).isEqualTo(7);
    }

    @Test
    void rejectsNullAccessTokenTtl() {
        assertThatThrownBy(() -> new JwtProperties("", null, Duration.ofDays(14)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("accessTokenTtl");
    }

    @Test
    void rejectsZeroAccessTokenTtl() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ZERO, Duration.ofDays(14)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("accessTokenTtl");
    }

    @Test
    void rejectsNegativeAccessTokenTtl() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofSeconds(-1), Duration.ofDays(14)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("accessTokenTtl");
    }

    @Test
    void rejectsNullRefreshTokenTtl() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofMinutes(30), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("refreshTokenTtl");
    }

    @Test
    void rejectsZeroRefreshTokenTtl() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofMinutes(30), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("refreshTokenTtl");
    }

    @Test
    void rejectsNegativeRefreshTokenTtl() {
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofMinutes(30), Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("refreshTokenTtl");
    }

    @Test
    void rejectsPublicOnlyP256Key() throws Exception {
        JwtProperties properties = new JwtProperties(publicKeyPem("secp256r1"), Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
    }

    @Test
    void rejectsNonP256PrivateKey() throws Exception {
        JwtProperties properties = new JwtProperties(privateKeyPem("secp384r1"), Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("P-256");
    }

    @Test
    void rejectsMismatchedPrivateAndPublicKeyBlocks() throws Exception {
        String pem = privateKeyPem("secp256r1") + "\n" + publicKeyPem("secp256r1");
        JwtProperties properties = new JwtProperties(pem, Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("match");
    }

    @Test
    void rejectsBlankPrivateKeyOutsideDevelopmentProfiles() {
        JwtProperties properties = new JwtProperties("", Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STOCK_JWT_PRIVATE_KEY");
    }

    @Test
    void rejectsTokenMissingTokenVersionClaim() throws Exception {
        KeyPair keyPair = keyPair("secp256r1");
        JwtProperties properties = new JwtProperties(pem("PRIVATE KEY", keyPair.getPrivate()), Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties, environment("prod"));
        String token = encodeToken(keyPair, claims -> claims
            .subject("42")
            .claim("role", Role.USER.name())
        );

        assertThatThrownBy(() -> jwtService.parse(token))
            .isInstanceOf(BadJwtException.class)
            .hasMessageContaining("tokenVersion");
    }

    @Test
    void rejectsTokenWithNonNumericSubject() throws Exception {
        KeyPair keyPair = keyPair("secp256r1");
        JwtProperties properties = new JwtProperties(pem("PRIVATE KEY", keyPair.getPrivate()), Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties, environment("prod"));
        String token = encodeToken(keyPair, claims -> claims
            .subject("not-a-number")
            .claim("role", Role.USER.name())
            .claim("tokenVersion", 1)
        );

        assertThatThrownBy(() -> jwtService.parse(token))
            .isInstanceOf(BadJwtException.class)
            .hasMessageContaining("sub");
    }

    private static MockEnvironment environment(String profile) {
        return new MockEnvironment().withProperty("spring.profiles.active", profile);
    }

    private static String encodeToken(KeyPair keyPair, Consumer<JwtClaimsSet.Builder> customizer) {
        ECKey key = new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
            .privateKey((ECPrivateKey) keyPair.getPrivate())
            .build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(30)));
        customizer.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.ES256).build(),
            claims.build()
        )).getTokenValue();
    }

    private static String privateKeyPem(String curveName) throws Exception {
        return pem("PRIVATE KEY", keyPair(curveName).getPrivate());
    }

    private static String publicKeyPem(String curveName) throws Exception {
        return pem("PUBLIC KEY", keyPair(curveName).getPublic());
    }

    private static KeyPair keyPair(String curveName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curveName));
        return generator.generateKeyPair();
    }

    private static String pem(String type, PrivateKey key) {
        return pem(type, key.getEncoded());
    }

    private static String pem(String type, PublicKey key) {
        return pem(type, key.getEncoded());
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
            + "\n-----END " + type + "-----";
    }
}
