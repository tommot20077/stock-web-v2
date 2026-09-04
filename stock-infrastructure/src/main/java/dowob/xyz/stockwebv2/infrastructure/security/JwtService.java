package dowob.xyz.stockwebv2.infrastructure.security;

import dowob.xyz.stockwebv2.common.model.Role;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties, Environment environment) {
        this.properties = properties;
        ECKey key = resolveKey(properties.privateKey(), environment);
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(key));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = NimbusJwtDecoder.withJwkSource(jwkSource)
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build();
    }

    public String createAccessToken(Long userId, Role role, int tokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(String.valueOf(userId))
            .issuedAt(now)
            .expiresAt(exp)
            .claim("role", role.name())
            .claim("tokenVersion", tokenVersion)
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public JwtClaims parse(String token) {
        Jwt jwt = decoder.decode(token);
        return new JwtClaims(
            parseSubject(jwt),
            parseRole(jwt),
            parseTokenVersion(jwt)
        );
    }

    public Map<String, Object> debugClaims(String token) {
        JwtClaims claims = parse(token);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sub", claims.userId());
        map.put("role", claims.role().name());
        map.put("tokenVersion", claims.tokenVersion());
        return map;
    }

    public record JwtClaims(Long userId, Role role, int tokenVersion) {
    }

    private Long parseSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (StringUtils.isBlank(subject)) {
            throw new BadJwtException("JWT sub claim is required");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw new BadJwtException("JWT sub claim must be numeric", exception);
        }
    }

    private Role parseRole(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (StringUtils.isBlank(role)) {
            throw new BadJwtException("JWT role claim is required");
        }
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException("JWT role claim is invalid", exception);
        }
    }

    private int parseTokenVersion(Jwt jwt) {
        Object tokenVersion = jwt.getClaim("tokenVersion");
        if (!(tokenVersion instanceof Number number)) {
            throw new BadJwtException("JWT tokenVersion claim must be numeric");
        }
        return number.intValue();
    }

    private ECKey resolveKey(String configuredKey, Environment environment) {
        if (StringUtils.isBlank(configuredKey)) {
            if (!environment.acceptsProfiles(Profiles.of("dev", "test", "e2e", "e2e-browser"))) {
                throw new IllegalStateException("STOCK_JWT_PRIVATE_KEY must be configured outside dev/test/e2e/e2e-browser profiles");
            }
            log.warn("stock.jwt.private-key is blank; generating an ephemeral development JWT key");
            return generateKey();
        }
        try {
            ECKey ecKey = parseJwk(configuredKey);
            validatePrivateP256Key(ecKey);
            return ecKey;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse STOCK_JWT_PRIVATE_KEY", exception);
        }
    }

    private ECKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            return new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                .privateKey((ECPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate development JWT key", exception);
        }
    }

    private void validatePrivateP256Key(ECKey ecKey) {
        if (!ecKey.isPrivate()) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must include EC private key material");
        }
        if (!Curve.P_256.equals(ecKey.getCurve())) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must use the P-256 curve for ES256");
        }
    }

    /**
     * 把設定值解析成 JWK。
     *
     * <p>設定格式是 <strong>EC JWK（JSON）</strong>而非 PEM。理由是 JWK 把私鑰分量 {@code d}
     * 與公鑰座標 {@code x} / {@code y} 放在同一個物件裡，所以：</p>
     *
     * <ul>
     *   <li>不需要從私鑰推導公鑰——JCA 沒有開放純量乘法，過去為此自行實作了約 60 行橢圓曲線
     *       體算術（{@code multiply} / {@code doublePoint} / {@code addPoints}），現已刪除。</li>
     *   <li>不需要比對「公鑰區塊與私鑰是否配對」——只有一個物件，不存在兩塊可能不一致的情形。</li>
     * </ul>
     *
     * <p>{@code kid} 缺漏時才補一個隨機值。設定裡有寫就沿用，因為 {@code kid} 應該隨金鑰走
     * 而不是隨啟動走：若日後對外發布 JWKS，每次重啟就換 {@code kid} 會讓消費端快取的金鑰集對不上。</p>
     *
     * @param value 設定值
     * @return 解析後的 EC JWK
     * @throws IllegalArgumentException 值仍是舊的 PEM 格式、非合法 JSON、或 kty 不是 EC 時
     */
    private ECKey parseJwk(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("-----BEGIN")) {
            throw new IllegalArgumentException(
                "STOCK_JWT_PRIVATE_KEY is still in the legacy PEM format; it must now be an EC JWK (JSON). "
                    + "Convert it without exposing the key to any third party: "
                    + "openssl pkey -in private.pem -pubout -out public.pem"
                    + " && java -cp <app.jar> " + JwkKeyConverter.class.getName() + " private.pem public.pem");
        }
        JWK jwk;
        try {
            jwk = JWK.parse(trimmed);
        } catch (ParseException exception) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must be a valid EC JWK (JSON)", exception);
        }
        if (!(jwk instanceof ECKey ecKey)) {
            /*
             * 不先擋下來的話，(ECKey) 轉型會丟 ClassCastException——啟動失敗訊息完全看不出
             * 是設定放錯了金鑰類型。
             */
            throw new IllegalArgumentException(
                "STOCK_JWT_PRIVATE_KEY must be an EC JWK for ES256, but kty was " + jwk.getKeyType());
        }
        if (ecKey.getKeyID() != null) {
            return ecKey;
        }
        return new ECKey.Builder(ecKey).keyID(UUID.randomUUID().toString()).build();
    }
}
