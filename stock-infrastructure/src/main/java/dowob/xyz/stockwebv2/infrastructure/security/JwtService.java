package dowob.xyz.stockwebv2.infrastructure.security;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import dowob.xyz.stockwebv2.common.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Pattern PEM_BLOCK_PATTERN = Pattern.compile(
        "-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----",
        Pattern.DOTALL
    );

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        ECKey key = resolveKey(properties.privateKey());
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
            Long.valueOf(jwt.getSubject()),
            Role.valueOf(jwt.getClaimAsString("role")),
            ((Number) jwt.getClaim("tokenVersion")).intValue()
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

    private ECKey resolveKey(String privateKeyPem) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            log.warn("stock.jwt.private-key is blank; generating an ephemeral development JWT key");
            return generateKey();
        }
        try {
            ECKey ecKey = parseEcPrivateKey(privateKeyPem);
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

    private ECKey parseEcPrivateKey(String pem) throws Exception {
        byte[] privateKeyBytes = null;
        byte[] publicKeyBytes = null;
        Matcher matcher = PEM_BLOCK_PATTERN.matcher(pem);
        while (matcher.find()) {
            String type = matcher.group(1);
            byte[] bytes = Base64.getMimeDecoder().decode(matcher.group(2));
            if ("PRIVATE KEY".equals(type)) {
                privateKeyBytes = bytes;
            } else if ("PUBLIC KEY".equals(type)) {
                publicKeyBytes = bytes;
            }
        }

        if (privateKeyBytes == null) {
            if (publicKeyBytes != null) {
                throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must be an EC private key, not a public-only key");
            }
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must contain a PKCS#8 EC private key PEM block");
        }

        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        ECPrivateKey privateKey;
        try {
            privateKey = (ECPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must be an EC private key", exception);
        }

        ECPublicKey derivedPublicKey = derivePublicKey(keyFactory, privateKey);
        ECPublicKey publicKey = derivedPublicKey;
        if (publicKeyBytes != null) {
            publicKey = parsePublicKey(keyFactory, publicKeyBytes);
            if (!publicKey.getW().equals(derivedPublicKey.getW())) {
                throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY public key block must match the private key");
            }
        }
        return new ECKey.Builder(toNimbusCurve(privateKey.getParams()), publicKey)
            .privateKey(privateKey)
            .keyID(UUID.randomUUID().toString())
            .build();
    }

    private ECPublicKey parsePublicKey(KeyFactory keyFactory, byte[] publicKeyBytes) {
        try {
            return (ECPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY public key block must be an EC public key", exception);
        }
    }

    private ECPublicKey derivePublicKey(KeyFactory keyFactory, ECPrivateKey privateKey) throws Exception {
        ECParameterSpec params = privateKey.getParams();
        ECPoint publicPoint = multiply(params.getGenerator(), privateKey.getS(), params);
        return (ECPublicKey) keyFactory.generatePublic(new ECPublicKeySpec(publicPoint, params));
    }

    private void validatePrivateP256Key(ECKey ecKey) {
        if (!ecKey.isPrivate()) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must include EC private key material");
        }
        if (!Curve.P_256.equals(ecKey.getCurve())) {
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must use the P-256 curve for ES256");
        }
    }

    private Curve toNimbusCurve(ECParameterSpec params) {
        int fieldSize = params.getCurve().getField().getFieldSize();
        if (fieldSize == 256) {
            return Curve.P_256;
        }
        if (fieldSize == 384) {
            return Curve.P_384;
        }
        if (fieldSize == 521) {
            return Curve.P_521;
        }
        throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY uses an unsupported EC curve");
    }

    private ECPoint multiply(ECPoint point, BigInteger scalar, ECParameterSpec params) {
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = point;
        for (int index = scalar.bitLength() - 1; index >= 0; index--) {
            result = doublePoint(result, params);
            if (scalar.testBit(index)) {
                result = addPoints(result, addend, params);
            }
        }
        return result;
    }

    private ECPoint doublePoint(ECPoint point, ECParameterSpec params) {
        if (ECPoint.POINT_INFINITY.equals(point)) {
            return point;
        }
        BigInteger p = fieldPrime(params);
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (BigInteger.ZERO.equals(y)) {
            return ECPoint.POINT_INFINITY;
        }

        BigInteger a = params.getCurve().getA();
        BigInteger numerator = x.pow(2).multiply(BigInteger.valueOf(3)).add(a).mod(p);
        BigInteger denominator = y.multiply(BigInteger.TWO).modInverse(p);
        BigInteger lambda = numerator.multiply(denominator).mod(p);
        BigInteger xr = lambda.pow(2).subtract(x.shiftLeft(1)).mod(p);
        BigInteger yr = lambda.multiply(x.subtract(xr)).subtract(y).mod(p);
        return new ECPoint(xr, yr);
    }

    private ECPoint addPoints(ECPoint first, ECPoint second, ECParameterSpec params) {
        if (ECPoint.POINT_INFINITY.equals(first)) {
            return second;
        }
        if (ECPoint.POINT_INFINITY.equals(second)) {
            return first;
        }

        BigInteger p = fieldPrime(params);
        BigInteger x1 = first.getAffineX();
        BigInteger y1 = first.getAffineY();
        BigInteger x2 = second.getAffineX();
        BigInteger y2 = second.getAffineY();
        if (x1.equals(x2)) {
            if (y1.add(y2).mod(p).equals(BigInteger.ZERO)) {
                return ECPoint.POINT_INFINITY;
            }
            return doublePoint(first, params);
        }

        BigInteger lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p);
        BigInteger xr = lambda.pow(2).subtract(x1).subtract(x2).mod(p);
        BigInteger yr = lambda.multiply(x1.subtract(xr)).subtract(y1).mod(p);
        return new ECPoint(xr, yr);
    }

    private BigInteger fieldPrime(ECParameterSpec params) {
        if (params.getCurve().getField() instanceof ECFieldFp field) {
            return field.getP();
        }
        throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must use a prime-field EC curve");
    }
}
