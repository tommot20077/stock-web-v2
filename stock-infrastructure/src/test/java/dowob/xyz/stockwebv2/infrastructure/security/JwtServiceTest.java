package dowob.xyz.stockwebv2.infrastructure.security;

import dowob.xyz.stockwebv2.common.model.Role;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
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
    void createsAndParsesAccessTokenWithConfiguredP256Jwk() throws Exception {
        JwtProperties properties = new JwtProperties(privateJwk("secp256r1"), Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties, environment("prod"));

        String token = jwtService.createAccessToken(42L, Role.USER, 7);
        JwtService.JwtClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo(Role.USER);
        assertThat(claims.tokenVersion()).isEqualTo(7);
    }

    @Test
    void keepsConfiguredKeyIdInsteadOfGeneratingOnePerStartup() throws Exception {
        /*
         * kid 應該隨金鑰走而不是隨啟動走：若之後要對外發布 JWKS，每次重啟就換 kid
         * 會讓別人快取的金鑰集對不上。設定裡有 kid 就必須沿用。
         */
        String jwk = jwkBuilder("secp256r1").keyID("stock-signing-2026").build().toJSONString();
        JwtProperties properties = new JwtProperties(jwk, Duration.ofMinutes(30), Duration.ofDays(14));

        JwtService first = new JwtService(properties, environment("prod"));
        JwtService second = new JwtService(properties, environment("prod"));

        assertThat(keyIdOf(first.createAccessToken(1L, Role.USER, 1))).isEqualTo("stock-signing-2026");
        assertThat(keyIdOf(second.createAccessToken(1L, Role.USER, 1))).isEqualTo("stock-signing-2026");
    }

    @Test
    void rejectsPemValueWithMigrationGuidance() throws Exception {
        /*
         * 舊格式是 PEM。直接丟一個「無法解析」的通用錯誤，維運只會看到一團 JSON 例外，
         * 不知道要做什麼；錯誤訊息必須指出新格式與轉換工具。
         */
        JwtProperties properties = new JwtProperties(privateKeyPem("secp256r1"), Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JWK")
            .hasMessageContaining("JwkKeyConverter");
    }

    @Test
    void rejectsJwkWithoutPrivateComponent() throws Exception {
        String publicOnly = jwkBuilder("secp256r1").build().toPublicJWK().toJSONString();
        JwtProperties properties = new JwtProperties(publicOnly, Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
    }

    @Test
    void rejectsNonP256Jwk() throws Exception {
        JwtProperties properties = new JwtProperties(privateJwk("secp384r1"), Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("P-256");
    }

    @Test
    void rejectsMalformedJwk() {
        JwtProperties properties = new JwtProperties("{\"kty\":\"EC\",", Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("STOCK_JWT_PRIVATE_KEY");
    }

    @Test
    void rejectsRsaJwk() throws Exception {
        /*
         * kty 不是 EC 時要在轉型前擋下，否則 (ECKey) 的 ClassCastException 會冒成
         * 一個看不出原因的啟動失敗。
         */
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String rsaJwk = new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
            .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
            .build()
            .toJSONString();
        JwtProperties properties = new JwtProperties(rsaJwk, Duration.ofMinutes(30), Duration.ofDays(14));

        assertThatThrownBy(() -> new JwtService(properties, environment("prod")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EC");
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
    void allowsGeneratedKeyUnderE2eBrowserProfile() {
        JwtProperties properties = new JwtProperties("", Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties, environment("e2e-browser"));

        String token = jwtService.createAccessToken(42L, Role.USER, 7);

        assertThat(jwtService.parse(token).userId()).isEqualTo(42L);
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
        JwtProperties properties = new JwtProperties(jwkOf(keyPair), Duration.ofMinutes(30), Duration.ofDays(14));
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
        JwtProperties properties = new JwtProperties(jwkOf(keyPair), Duration.ofMinutes(30), Duration.ofDays(14));
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

    /** 新設定格式：完整的 EC JWK（含私鑰分量 d）。 */
    private static String privateJwk(String curveName) throws Exception {
        return jwkBuilder(curveName).build().toJSONString();
    }

    private static ECKey.Builder jwkBuilder(String curveName) throws Exception {
        return jwkBuilderOf(keyPair(curveName));
    }

    private static String jwkOf(KeyPair keyPair) {
        return jwkBuilderOf(keyPair).build().toJSONString();
    }

    private static ECKey.Builder jwkBuilderOf(KeyPair keyPair) {
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        return new ECKey.Builder(Curve.forECParameterSpec(publicKey.getParams()), publicKey)
            .privateKey((ECPrivateKey) keyPair.getPrivate());
    }

    /** 舊設定格式，只用於「PEM 值要給出遷移指引」那條測試。 */
    private static String privateKeyPem(String curveName) throws Exception {
        return pem("PRIVATE KEY", keyPair(curveName).getPrivate());
    }

    private static String keyIdOf(String token) {
        String header = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))));
        int start = header.indexOf("\"kid\":\"") + 7;
        return header.substring(start, header.indexOf('"', start));
    }

    private static KeyPair keyPair(String curveName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curveName));
        return generator.generateKeyPair();
    }

    private static String pem(String type, PrivateKey key) {
        return pem(type, key.getEncoded());
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
            + "\n-----END " + type + "-----";
    }
}
