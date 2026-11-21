package dowob.xyz.stockwebv2.start.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * repo 根 {@code .env.example} 的 access token TTL 成對守門（G-05-10 / D-22 / T-05-12）。
 *
 * <p><strong>為什麼需要</strong>：{@code dev} 是預設 profile，{@code application-dev.yaml} 以
 * {@code spring.config.import: optional:file:.env[.properties]} 匯入 repo 根的 {@code .env}，而 README 叫開發者
 * {@code cp .env.example .env}。範本裡的 {@code STOCK_JWT_ACCESS_TOKEN_TTL} 與
 * {@code STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL}（或缺少時 {@code application.yaml} 的 placeholder 預設）只要任一漂移，
 * {@code AccessTokenTtlConsistencyGuard} 就以 {@code Duration#equals} 拒絕啟動（D-22，沒有 profile 豁免）。</p>
 *
 * <p>05-05 的 grep 閘門只看 {@code stock-start/src/{main,test}/resources}，看不到 repo 根範本，舊的 JWT TTL 因此
 * 留在範本裡；本測試是唯一能看到範本的常駐閘門。</p>
 *
 * <p><strong>語意</strong>：範本以 {@link Properties} 解析（與 {@code .env[.properties]} 的 properties 格式一致，
 * {@code #} / {@code !} 開頭為註解）；有效值 = 範本有設定就用範本值，否則用 {@code application.yaml} 的 placeholder
 * 預設；兩者 {@link Duration#parse} 後必須相等。解析 / 比對 helper 與下方合成輸入的自我驗證共用。</p>
 *
 * <p>Surefire 的工作目錄是模組目錄（{@code stock-start}），所以範本路徑是 {@code ../.env.example}；
 * 找不到時直接失敗，不 skip。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName(".env.example access token TTL 成對守門")
class EnvExampleAccessTokenTtlTest {

    /** repo 根的範本（Surefire 工作目錄為 stock-start 模組目錄）。 */
    private static final Path ENV_EXAMPLE = Path.of("..", ".env.example");

    /** JWT access token TTL 的環境變數名。 */
    static final String JWT_ENV_KEY = "STOCK_JWT_ACCESS_TOKEN_TTL";

    /** browser access cookie TTL 的環境變數名。 */
    static final String COOKIE_ENV_KEY = "STOCK_AUTH_COOKIE_ACCESS_TOKEN_TTL";

    /** 與 {@code InfraProfileDefaultsTest} 相同：取出 {@code ${ENV:default}} 的 default。 */
    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]*)}");

    @Test
    @DisplayName("照 README 複製 .env.example 後，有效 JWT access TTL 等於有效 cookie access TTL")
    void envExampleAccessTokenTtlsArePaired() throws IOException {
        assertThat(Files.isRegularFile(ENV_EXAMPLE))
            .as("找不到 repo 根範本 %s（工作目錄 %s）", ENV_EXAMPLE, Path.of("").toAbsolutePath())
            .isTrue();
        Properties template;
        try (Reader reader = Files.newBufferedReader(ENV_EXAMPLE, StandardCharsets.UTF_8)) {
            template = parseTemplate(reader);
        }

        Duration jwt = effectiveTtl(template, JWT_ENV_KEY, yamlDefault("stock.jwt.access-token-ttl"));
        Duration cookie = effectiveTtl(template, COOKIE_ENV_KEY, yamlDefault("stock.auth.cookie.access-token-ttl"));

        assertThat(jwt)
            .as(".env.example 的 %s 必須與 %s（範本值或 application.yaml 預設）相等，否則 dev profile 被 "
                + "AccessTokenTtlConsistencyGuard 拒絕啟動（D-22）", JWT_ENV_KEY, COOKIE_ENV_KEY)
            .isEqualTo(cookie);
    }

    @Test
    @DisplayName("自我驗證：只設 JWT TTL（PT1H）、cookie 走預設 → 判定不成對")
    void selfCheckJwtOnlyOverrideIsDetected() throws IOException {
        Properties template = parseTemplate(new StringReader(JWT_ENV_KEY + "=PT1H\n"));

        assertThat(accessTtlsMatch(template, "PT15M", "PT15M")).isFalse();
    }

    @Test
    @DisplayName("自我驗證：兩個 TTL 同設 PT20M → 判定成對")
    void selfCheckBothOverriddenEquallyMatch() throws IOException {
        Properties template = parseTemplate(new StringReader(
            JWT_ENV_KEY + "=PT20M\n" + COOKIE_ENV_KEY + "=PT20M\n"
        ));

        assertThat(accessTtlsMatch(template, "PT15M", "PT15M")).isTrue();
    }

    @Test
    @DisplayName("自我驗證：註解掉的 TTL 行不算設定，兩邊都走預設")
    void selfCheckCommentedLineIsNotASetting() throws IOException {
        Properties template = parseTemplate(new StringReader(
            "# " + JWT_ENV_KEY + "=PT1H\n" + "! " + COOKIE_ENV_KEY + "=PT2H\n"
        ));

        assertThat(template.containsKey(JWT_ENV_KEY)).isFalse();
        assertThat(template.containsKey(COOKIE_ENV_KEY)).isFalse();
        assertThat(accessTtlsMatch(template, "PT15M", "PT15M")).isTrue();
    }

    /**
     * 以 properties 語意解析範本內容（{@code #} / {@code !} 註解、空白行皆忽略）。
     *
     * @param reader 範本內容
     * @return 範本中實際設定的鍵值
     * @throws IOException 讀取失敗
     */
    static Properties parseTemplate(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        return properties;
    }

    /**
     * 有效 TTL：範本有設定就用範本值，否則用 yaml 預設。
     *
     * @param template    範本鍵值
     * @param envKey      環境變數名
     * @param yamlDefault {@code application.yaml} placeholder 的預設值
     * @return 解析後的 TTL
     */
    static Duration effectiveTtl(Properties template, String envKey, String yamlDefault) {
        return Duration.parse(template.getProperty(envKey, yamlDefault).trim());
    }

    /**
     * 兩個 access TTL 的有效值是否相等（與 {@code AccessTokenTtlConsistencyGuard} 相同的 {@link Duration#equals} 語意）。
     *
     * @param template          範本鍵值
     * @param jwtYamlDefault    JWT TTL 的 yaml 預設
     * @param cookieYamlDefault cookie TTL 的 yaml 預設
     * @return 相等時為 {@code true}
     */
    static boolean accessTtlsMatch(Properties template, String jwtYamlDefault, String cookieYamlDefault) {
        return effectiveTtl(template, JWT_ENV_KEY, jwtYamlDefault)
            .equals(effectiveTtl(template, COOKIE_ENV_KEY, cookieYamlDefault));
    }

    /**
     * 讀 {@code application.yaml} 指定 key 的 placeholder 預設值（比照 {@code InfraProfileDefaultsTest#valueOf}）。
     *
     * @param key 設定鍵
     * @return placeholder 預設值；不是 placeholder 時回原值
     * @throws IOException 讀取失敗
     */
    private static String yamlDefault(String key) throws IOException {
        PropertySource<?> source = new YamlPropertySourceLoader()
            .load("application.yaml", new ClassPathResource("application.yaml"))
            .getFirst();
        Object raw = source.getProperty(key);
        assertThat(raw).as("application.yaml " + key + " 必須明確設定").isNotNull();
        Matcher m = PLACEHOLDER_DEFAULT.matcher(raw.toString());
        return m.matches() ? m.group(1) : raw.toString();
    }
}
