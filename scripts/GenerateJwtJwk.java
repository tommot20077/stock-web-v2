import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * 產生一把全新的 {@code STOCK_JWT_PRIVATE_KEY}(P-256 EC JWK,含私鑰分量 {@code d})。
 *
 * <p>只用 JDK,不需要先建置專案,也不需要 openssl:</p>
 * <pre>
 * java scripts/GenerateJwtJwk.java                       # kid 預設 stock-signing-yyyyMMdd
 * java scripts/GenerateJwtJwk.java stock-signing-prod-2026
 * </pre>
 *
 * <p>存在的理由與 {@code JwkKeyConverter} 相同:不要用線上工具產生或轉換簽章金鑰。
 * 已經有 PEM 金鑰、想沿用同一把(既有 token 不失效)時用 {@code JwkKeyConverter};
 * 要換一把新的時用本程式——換鑰後所有既有 access / refresh token 立即失效,使用者需重新登入。</p>
 *
 * <p>{@code kid} 固定寫進 JWK:{@code JwtService} 有寫就沿用,沒寫才每次啟動補隨機值,
 * 日後若對外發布 JWKS,會讓消費端快取的金鑰集對不上。</p>
 *
 * <p>輸出到 stdout,是一行 JSON。它含有私鑰分量,請比照密碼處理——直接導向檔案或剪貼簿,
 * 不要進 shell history、不要寫進 log、不要 commit。</p>
 *
 * @author Yuan
 * @version 1.0.0
 */
public final class GenerateJwtJwk {
    /** P-256 座標與純量的固定位元組長度(RFC 7518 §6.2.1.2 要求補滿,不可省略前導零)。 */
    private static final int P256_FIELD_BYTES = 32;

    private GenerateJwtJwk() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            System.err.println("Usage: java scripts/GenerateJwtJwk.java [kid]");
            System.exit(2);
            return;
        }
        String kid = args.length == 1
            ? args[0]
            : "stock-signing-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!kid.matches("[A-Za-z0-9._-]{1,64}")) {
            System.err.println("kid must be 1-64 characters of [A-Za-z0-9._-]");
            System.exit(2);
            return;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

        System.out.println("{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\""
            + ",\"x\":\"" + base64Url(publicKey.getW().getAffineX()) + "\""
            + ",\"y\":\"" + base64Url(publicKey.getW().getAffineY()) + "\""
            + ",\"d\":\"" + base64Url(privateKey.getS()) + "\"}");
    }

    /**
     * 把非負整數編成固定 32 位元組的 big-endian base64url(無 padding)。
     *
     * <p>{@link BigInteger#toByteArray()} 會多一個符號位元組或少掉前導零,
     * 直接編碼會產出 31 或 33 位元組的座標,Nimbus 會拒收或算錯。</p>
     */
    private static String base64Url(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[P256_FIELD_BYTES];
        int copy = Math.min(raw.length, P256_FIELD_BYTES);
        System.arraycopy(raw, raw.length - copy, fixed, P256_FIELD_BYTES - copy, copy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }
}
