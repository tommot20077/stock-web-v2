package dowob.xyz.stockwebv2.start.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI / Swagger UI 的預設曝露。
 *
 * <p>security.md 明文 production 必須關閉，但原本 {@code application.yaml} 與 {@code application-demo.yaml}
 * 的預設值都是 {@code true}，而 {@code SecurityConfig} 又對 {@code /v3/api-docs/**}、{@code /swagger-ui/**}
 * permitAll —— 忘了設環境變數就等於把整份 API 規格公開（2026-09-02 安全審查 M-3）。
 *
 * <p>這裡鎖的是「不設環境變數時的預設值必須是關閉」。需要文件的 profile（dev / test / e2e）自行開啟。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("OpenAPI 預設不對外曝露")
class OpenApiExposureDefaultsTest {

    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]*)}");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application.yaml", "application-demo.yaml"})
    @DisplayName("未設環境變數時 api-docs 與 swagger-ui 皆為關閉")
    void openApiIsDisabledByDefault(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        assertThat(sources).isNotEmpty();
        PropertySource<?> source = sources.getFirst();

        assertThat(defaultOf(source, "springdoc.api-docs.enabled")).as(file + " api-docs").isEqualTo("false");
        assertThat(defaultOf(source, "springdoc.swagger-ui.enabled")).as(file + " swagger-ui").isEqualTo("false");
    }

    /**
     * 取出屬性值；若為 {@code ${ENV:default}} 形式則回傳 default 部分。
     */
    private String defaultOf(PropertySource<?> source, String key) {
        Object raw = source.getProperty(key);
        assertThat(raw).as(key + " 必須明確設定").isNotNull();
        Matcher m = PLACEHOLDER_DEFAULT.matcher(raw.toString());
        return m.matches() ? m.group(1) : raw.toString();
    }
}
