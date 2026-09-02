package dowob.xyz.stockwebv2.infrastructure.security;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次性維運工具：把舊的 PEM 格式金鑰轉成 {@code STOCK_JWT_PRIVATE_KEY} 需要的 EC JWK。
 *
 * <p>存在的理由是「不要把簽章金鑰貼到線上轉換器」。手工湊 {@code x} / {@code y} 的
 * base64url 既容易出錯又沒必要，而唯一方便的替代方案（線上工具）等同把生產環境的
 * 私鑰交給第三方。</p>
 *
 * <h2>用法</h2>
 * <pre>
 * # 公鑰本來就內嵌在 PKCS#8 私鑰檔裡，pubout 只是把它讀出來，不是重新計算
 * openssl pkey -in private.pem -pubout -out public.pem
 * java -cp &lt;app.jar&gt; dowob.xyz.stockwebv2.infrastructure.security.JwkKeyConverter private.pem public.pem
 * </pre>
 *
 * <p>若兩個 PEM 區塊已經在同一個檔案（舊設定支援的形式），給一個路徑即可。</p>
 *
 * <p>輸出寫到 stdout，是一行 JSON。它含有私鑰分量，請比照密碼處理——不要進 shell history、
 * 不要寫進 log。</p>
 *
 * @author Yuan
 * @version 1.0.0
 */
public final class JwkKeyConverter {
    private static final Pattern PEM_BLOCK_PATTERN = Pattern.compile(
        "-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----",
        Pattern.DOTALL
    );

    private JwkKeyConverter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: JwkKeyConverter <private.pem> [public.pem]");
            System.err.println("  The public key is embedded in a PKCS#8 EC private key; extract it with:");
            System.err.println("    openssl pkey -in private.pem -pubout -out public.pem");
            System.exit(2);
            return;
        }

        StringBuilder pem = new StringBuilder();
        for (String arg : args) {
            pem.append(Files.readString(Path.of(arg), StandardCharsets.UTF_8)).append('\n');
        }
        System.out.println(convert(pem.toString()).toJSONString());
    }

    /**
     * 把 PEM 文字（需含一個 PRIVATE KEY 與一個 PUBLIC KEY 區塊）轉成 EC JWK。
     *
     * @param pem PEM 文字，兩個區塊可以在同一段或分別來自不同檔案
     * @return 含私鑰分量的 EC JWK
     * @throws IllegalArgumentException 區塊缺漏、非 EC 金鑰、或公私鑰不成對時
     */
    static ECKey convert(String pem) throws Exception {
        byte[] privateKeyBytes = null;
        byte[] publicKeyBytes = null;
        Matcher matcher = PEM_BLOCK_PATTERN.matcher(pem);
        while (matcher.find()) {
            byte[] bytes = Base64.getMimeDecoder().decode(matcher.group(2));
            if ("PRIVATE KEY".equals(matcher.group(1))) {
                privateKeyBytes = bytes;
            } else if ("PUBLIC KEY".equals(matcher.group(1))) {
                publicKeyBytes = bytes;
            }
        }
        if (privateKeyBytes == null) {
            throw new IllegalArgumentException("Input must contain a PKCS#8 EC PRIVATE KEY block");
        }
        if (publicKeyBytes == null) {
            throw new IllegalArgumentException(
                "Input must contain a PUBLIC KEY block; extract it with: openssl pkey -in private.pem -pubout");
        }

        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        ECPrivateKey privateKey = (ECPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        ECPublicKey publicKey = (ECPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        requirePair(privateKey, publicKey);

        return new ECKey.Builder(Curve.forECParameterSpec(publicKey.getParams()), publicKey)
            .privateKey(privateKey)
            .build();
    }

    /**
     * 用「私鑰簽、公鑰驗」確認兩者是同一對。
     *
     * <p>這是標準 JCA 的做法，取代了過去在啟動路徑上自行推導公鑰再比對座標的方式。
     * 放在這裡（一次性轉換）而不是啟動路徑，是因為轉換完成後 JWK 只有一個物件，
     * 執行期已經不存在「兩塊可能不配」的問題，不需要每次開機再驗一遍。</p>
     *
     * @param privateKey 私鑰
     * @param publicKey  公鑰
     * @throws IllegalArgumentException 兩者不成對時
     */
    private static void requirePair(ECPrivateKey privateKey, ECPublicKey publicKey) throws Exception {
        byte[] probe = "stock-web-v2-jwt-key-probe".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(probe);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(probe);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException("The PUBLIC KEY block does not match the PRIVATE KEY block");
        }
    }
}
