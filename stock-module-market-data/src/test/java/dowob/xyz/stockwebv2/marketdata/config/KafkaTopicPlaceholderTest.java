package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.consumer.PriceWriterConsumer;
import dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer;
import dowob.xyz.stockwebv2.marketdata.ingest.MarketDataIngestService;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Kafka topic / consumer group placeholder 的預設值守門(D-29、T-05-15)。
 *
 * <p>為了讓 market-data IT 在共用 Kafka 下以唯一 topic / group 隔離,正式程式的 topic 與 group
 * 改為 {@code ${property:預設值}} placeholder。本測試鎖住的不變量是:<strong>沒有設定 property 時,
 * 正式環境訂閱 / 發送的 topic 與 group 逐字等於改動前的常數</strong>
 * ({@code market.price.tick.v1}、{@code market.price.backfill.v1}、{@code market-data.persist}、
 * {@code market-data.ws-broadcast})。任何人改了 placeholder 的 property 名或預設值,這裡會先紅。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Kafka topic / group placeholder 預設值不變")
class KafkaTopicPlaceholderTest {

    @Test
    @DisplayName("PriceWriterConsumer 訂閱 tick + backfill placeholder,group 為 persist placeholder")
    void priceWriterListenerUsesPlaceholders() throws NoSuchMethodException {
        KafkaListener listener = PriceWriterConsumer.class
                .getMethod("consume", List.class)
                .getAnnotation(KafkaListener.class);

        assertThat(listener.topics()).containsExactly(
                "${market-data.kafka.topics.price-tick:market.price.tick.v1}",
                "${market-data.kafka.topics.price-backfill:market.price.backfill.v1}");
        assertThat(listener.groupId()).isEqualTo("${market-data.kafka.groups.persist:market-data.persist}");
    }

    @Test
    @DisplayName("WsBroadcastConsumer 只訂閱 tick placeholder,group 為 ws-broadcast placeholder")
    void wsBroadcastListenerUsesPlaceholders() throws NoSuchMethodException {
        KafkaListener listener = WsBroadcastConsumer.class
                .getMethod("onTick", PriceTickEvent.class)
                .getAnnotation(KafkaListener.class);

        assertThat(listener.topics()).containsExactly(
                "${market-data.kafka.topics.price-tick:market.price.tick.v1}");
        assertThat(listener.groupId())
                .isEqualTo("${market-data.kafka.groups.ws-broadcast:market-data.ws-broadcast}");
    }

    @Test
    @DisplayName("placeholder 冒號後的預設值等於 KafkaConfig 的 topic / group 常數")
    void placeholderDefaultsEqualConstants() {
        assertThat(defaultOf(KafkaConfig.TOPIC_PRICE_TICK_PLACEHOLDER)).isEqualTo(KafkaConfig.TOPIC_PRICE_TICK);
        assertThat(defaultOf(KafkaConfig.TOPIC_PRICE_BACKFILL_PLACEHOLDER))
                .isEqualTo(KafkaConfig.TOPIC_PRICE_BACKFILL);
        assertThat(defaultOf(KafkaConfig.GROUP_PERSIST_PLACEHOLDER)).isEqualTo(KafkaConfig.GROUP_PERSIST);
        assertThat(defaultOf(KafkaConfig.GROUP_WS_BROADCAST_PLACEHOLDER))
                .isEqualTo(KafkaConfig.GROUP_WS_BROADCAST);

        assertThat(KafkaConfig.TOPIC_PRICE_TICK).isEqualTo("market.price.tick.v1");
        assertThat(KafkaConfig.TOPIC_PRICE_BACKFILL).isEqualTo("market.price.backfill.v1");
        assertThat(KafkaConfig.GROUP_PERSIST).isEqualTo("market-data.persist");
        assertThat(KafkaConfig.GROUP_WS_BROADCAST).isEqualTo("market-data.ws-broadcast");
    }

    @Test
    @DisplayName("未設 property 時 Spring 解析 placeholder 得到正式常數;設定後得到覆寫值")
    void placeholdersResolveToDefaultsUnlessOverridden() {
        StandardEnvironment empty = new StandardEnvironment();
        assertThat(empty.resolveRequiredPlaceholders(KafkaConfig.TOPIC_PRICE_TICK_PLACEHOLDER))
                .isEqualTo("market.price.tick.v1");
        assertThat(empty.resolveRequiredPlaceholders(KafkaConfig.TOPIC_PRICE_BACKFILL_PLACEHOLDER))
                .isEqualTo("market.price.backfill.v1");
        assertThat(empty.resolveRequiredPlaceholders(KafkaConfig.GROUP_PERSIST_PLACEHOLDER))
                .isEqualTo("market-data.persist");
        assertThat(empty.resolveRequiredPlaceholders(KafkaConfig.GROUP_WS_BROADCAST_PLACEHOLDER))
                .isEqualTo("market-data.ws-broadcast");

        MockEnvironment overridden = new MockEnvironment()
                .withProperty(KafkaConfig.PROP_TOPIC_PRICE_TICK, "it.tick")
                .withProperty(KafkaConfig.PROP_GROUP_PERSIST, "it.persist");
        assertThat(overridden.resolveRequiredPlaceholders(KafkaConfig.TOPIC_PRICE_TICK_PLACEHOLDER))
                .isEqualTo("it.tick");
        assertThat(overridden.resolveRequiredPlaceholders(KafkaConfig.GROUP_PERSIST_PLACEHOLDER))
                .isEqualTo("it.persist");
    }

    @Test
    @DisplayName("NewTopic bean 以注入的 topic 名命名,DLT 為 <topic>.DLT")
    void topicBeansFollowInjectedNames() {
        KafkaConfig config = new KafkaConfig();

        NewTopic tick = config.priceTickTopic(KafkaConfig.TOPIC_PRICE_TICK);
        NewTopic tickDlt = config.priceTickDltTopic(KafkaConfig.TOPIC_PRICE_TICK);
        NewTopic backfillDlt = config.priceBackfillDltTopic("it.backfill");

        assertThat(tick.name()).isEqualTo("market.price.tick.v1");
        assertThat(tickDlt.name()).isEqualTo(KafkaConfig.TOPIC_PRICE_TICK_DLT);
        assertThat(config.priceBackfillTopic("it.backfill").name()).isEqualTo("it.backfill");
        assertThat(backfillDlt.name()).isEqualTo("it.backfill.DLT");
    }

    @Test
    @DisplayName("MarketDataIngestService:1 參數建構子送常數 topic,3 參數建構子送注入的 topic")
    @SuppressWarnings("unchecked")
    void ingestServiceSendsToConfiguredTopics() {
        PriceTickEvent event = PriceTickEvent.of(42L, "AAPL", new BigDecimal("1"), null, "mock");

        KafkaTemplate<String, Object> defaults = mock(KafkaTemplate.class);
        MarketDataIngestService defaultService = new MarketDataIngestService(defaults);
        defaultService.publishTick(event);
        defaultService.publishBackfillTick(event);
        verify(defaults).send(KafkaConfig.TOPIC_PRICE_TICK, "42", event);
        verify(defaults).send(KafkaConfig.TOPIC_PRICE_BACKFILL, "42", event);

        KafkaTemplate<String, Object> custom = mock(KafkaTemplate.class);
        MarketDataIngestService customService = new MarketDataIngestService(custom, "it.tick", "it.backfill");
        customService.publishTick(event);
        customService.publishBackfillTick(event);
        verify(custom).send("it.tick", "42", event);
        verify(custom).send("it.backfill", "42", event);
    }

    /**
     * 取出 {@code ${name:default}} 的 default 段。
     *
     * @param placeholder Spring property placeholder
     * @return 冒號後、結尾大括號前的預設值
     */
    private static String defaultOf(String placeholder) {
        assertThat(placeholder).startsWith("${").endsWith("}").contains(":");
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }
}
