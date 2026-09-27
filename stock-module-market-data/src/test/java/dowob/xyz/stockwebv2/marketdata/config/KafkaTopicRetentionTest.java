package dowob.xyz.stockwebv2.marketdata.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行情 topic 的保留期(2026-09-28 Yuan 裁決:行情 24 小時、DLT 7 天)。
 *
 * <p>保留期就是 consumer / DB 故障時可補讀的時間窗:1 小時太短(半夜出事隔天才處理即遺失),
 * broker 預設 7 天對高頻行情又太占磁碟。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("行情 topic 保留期")
class KafkaTopicRetentionTest {

    private static final String ONE_DAY_MS = String.valueOf(24L * 60 * 60 * 1000);
    private static final String SEVEN_DAYS_MS = String.valueOf(7L * 24 * 60 * 60 * 1000);

    private final KafkaConfig config = new KafkaConfig();

    @Test
    @DisplayName("tick 與 backfill topic 保留 24 小時")
    void priceTopics_retainOneDay() {
        assertThat(retention(config.priceTickTopic())).isEqualTo(ONE_DAY_MS);
        assertThat(retention(config.priceBackfillTopic())).isEqualTo(ONE_DAY_MS);
    }

    @Test
    @DisplayName("DLT 保留 7 天,供事後調查")
    void deadLetterTopics_retainSevenDays() {
        assertThat(retention(config.priceTickDltTopic())).isEqualTo(SEVEN_DAYS_MS);
        assertThat(retention(config.priceBackfillDltTopic())).isEqualTo(SEVEN_DAYS_MS);
    }

    private static String retention(NewTopic topic) {
        return topic.configs() == null ? null : topic.configs().get(TopicConfig.RETENTION_MS_CONFIG);
    }
}
