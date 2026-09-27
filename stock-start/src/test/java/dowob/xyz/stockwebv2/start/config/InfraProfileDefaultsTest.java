package dowob.xyz.stockwebv2.start.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基礎設施相關的 profile 預設值(2026-09-28 Yuan 裁決)。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("基礎設施 profile 預設值")
class InfraProfileDefaultsTest {

    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]*)}");

    @Test
    @DisplayName("KafkaAdmin 會把 topic 設定(retention)套用到已存在的 topic")
    void kafkaAdminModifiesExistingTopicConfigs() throws IOException {
        assertThat(valueOf("application.yaml", "spring.kafka.admin.modify-topic-configs")).isEqualTo("true");
    }

    @Test
    @DisplayName("e2e-browser 的 Redis 預設 DB 1,與其他 profile 一致(redis-convention)")
    void e2eBrowserUsesRedisDatabaseOne() throws IOException {
        assertThat(valueOf("application-e2e-browser.yaml", "spring.data.redis.database")).isEqualTo("1");
    }

    private static String valueOf(String file, String key) throws IOException {
        PropertySource<?> source = new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).getFirst();
        Object raw = source.getProperty(key);
        assertThat(raw).as(file + " " + key + " 必須明確設定").isNotNull();
        Matcher m = PLACEHOLDER_DEFAULT.matcher(raw.toString());
        return m.matches() ? m.group(1) : raw.toString();
    }
}
