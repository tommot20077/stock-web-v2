package dowob.xyz.stockwebv2.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwkKeyConverter} 的測試。
 *
 * <p>這個工具是舊 PEM 設定轉到新 JWK 設定的<strong>唯一</strong>安全路徑
 * （替代方案是把生產私鑰貼到線上轉換器），所以它輸出的東西必須被證明真的能用：
 * 下方 {@code producesJwkAcceptedByJwtService} 把轉換結果直接餵回 {@link JwtService}，
 * 而不是只比對欄位長相。</p>
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("PEM → JWK 轉換工具")
class JwkKeyConverterTest {

    @Test
    @DisplayName("轉換結果能被 JwtService 接受並完成簽發與解析的來回")
    void producesJwkAcceptedByJwtService() throws Exception {
        KeyPair keyPair = keyPair("secp256r1");
        String pem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())
            + "\n" + pem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        ECKey jwk = JwkKeyConverter.convert(pem);

        JwtService jwtService = new JwtService(
            new JwtProperties(jwk.toJSONString(), Duration.ofMinutes(30), Duration.ofDays(14)),
            new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active", "prod"));
        String token = jwtService.createAccessToken(42L, dowob.xyz.stockwebv2.common.model.Role.USER, 7);

        assertThat(jwtService.parse(token).userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("轉出的 JWK 保留私鑰分量與 P-256 曲線")
    void keepsPrivateComponentAndCurve() throws Exception {
        KeyPair keyPair = keyPair("secp256r1");
        String pem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())
            + "\n" + pem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        ECKey jwk = JwkKeyConverter.convert(pem);

        assertThat(jwk.isPrivate()).isTrue();
        assertThat(jwk.getCurve()).isEqualTo(Curve.P_256);
    }

    @Test
    @DisplayName("公私鑰不成對時以簽章探針擋下，不會產出一把永遠驗不過的 JWK")
    void rejectsMismatchedKeyPair() throws Exception {
        /*
         * 這是轉換階段最危險的手滑：兩個檔案來自不同金鑰。若不擋，產出的 JWK 會讓
         * decoder 永遠驗不過自己簽的 token，而且要到執行期才會發現。
         */
        String pem = pem("PRIVATE KEY", keyPair("secp256r1").getPrivate().getEncoded())
            + "\n" + pem("PUBLIC KEY", keyPair("secp256r1").getPublic().getEncoded());

        assertThatThrownBy(() -> JwkKeyConverter.convert(pem))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("只給私鑰區塊時指出要用 openssl pkey -pubout 取出公鑰")
    void requiresPublicKeyBlockWithGuidance() throws Exception {
        String pem = pem("PRIVATE KEY", keyPair("secp256r1").getPrivate().getEncoded());

        assertThatThrownBy(() -> JwkKeyConverter.convert(pem))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("openssl pkey");
    }

    @Test
    @DisplayName("缺少私鑰區塊時明確拒絕")
    void requiresPrivateKeyBlock() throws Exception {
        String pem = pem("PUBLIC KEY", keyPair("secp256r1").getPublic().getEncoded());

        assertThatThrownBy(() -> JwkKeyConverter.convert(pem))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PRIVATE KEY");
    }

    private static KeyPair keyPair(String curveName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curveName));
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
            + "\n-----END " + type + "-----";
    }
}
