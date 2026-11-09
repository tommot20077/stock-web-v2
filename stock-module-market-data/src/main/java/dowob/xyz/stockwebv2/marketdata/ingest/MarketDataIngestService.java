package dowob.xyz.stockwebv2.marketdata.ingest;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Market data ingest 入口 — 封裝 Kafka publish 細節。
 *
 * <p>即時 tick 與歷史 backfill 走分流 topic，確保歷史回填灌入不會推升即時 consumer lag。
 * Partition key 採 assetId，確保同一 asset 的事件按時間順序處理。
 *
 * <p>topic 名稱由 {@link KafkaConfig#TOPIC_PRICE_TICK_PLACEHOLDER} /
 * {@link KafkaConfig#TOPIC_PRICE_BACKFILL_PLACEHOLDER} 解析;placeholder 只為讓 IT 以唯一 topic 隔離(D-29),
 * 正式環境不設定 property,送出的 topic 仍是 {@value KafkaConfig#TOPIC_PRICE_TICK} /
 * {@value KafkaConfig#TOPIC_PRICE_BACKFILL}。
 *
 * @author Yuan
 * @version 1.1.0
 */
@Service
public class MarketDataIngestService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private final String tickTopic;

    private final String backfillTopic;

    /**
     * 以正式 topic 常數建立(單元測試與手動組裝用)。
     *
     * @param kafkaTemplate Spring Boot auto-config 提供的 KafkaTemplate (JSON serde)
     */
    public MarketDataIngestService(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, KafkaConfig.TOPIC_PRICE_TICK, KafkaConfig.TOPIC_PRICE_BACKFILL);
    }

    /**
     * Spring 注入用建構子:topic 名稱由 placeholder 解析,未設 property 時等於正式常數。
     *
     * @param kafkaTemplate Spring Boot auto-config 提供的 KafkaTemplate (JSON serde)
     * @param tickTopic     即時 tick topic 名稱,不可為 {@code null}
     * @param backfillTopic 歷史回填 topic 名稱,不可為 {@code null}
     * @throws NullPointerException 任一 topic 為 {@code null}
     */
    @Autowired
    public MarketDataIngestService(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value(KafkaConfig.TOPIC_PRICE_TICK_PLACEHOLDER) String tickTopic,
            @Value(KafkaConfig.TOPIC_PRICE_BACKFILL_PLACEHOLDER) String backfillTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.tickTopic = Objects.requireNonNull(tickTopic, "tickTopic must not be null");
        this.backfillTopic = Objects.requireNonNull(backfillTopic, "backfillTopic must not be null");
    }

    /**
     * 將即時 tick 推至 tick topic(預設 {@link KafkaConfig#TOPIC_PRICE_TICK})。
     *
     * <p>Partition key 以 {@code assetId.toString()} 傳入，確保相同 asset 的事件
     * 落在同一 partition 以維持時間順序。
     *
     * @param event 待發送的 PriceTickEvent，不可為 {@code null}
     * @return Kafka send future，可用來追蹤 broker ack
     * @throws NullPointerException 若 {@code event} 為 {@code null}
     */
    public CompletableFuture<SendResult<String, Object>> publishTick(PriceTickEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return kafkaTemplate.send(
                tickTopic,
                String.valueOf(event.assetId()),
                event);
    }

    /**
     * 將歷史回填 tick 推至 backfill topic(預設 {@link KafkaConfig#TOPIC_PRICE_BACKFILL})。
     *
     * <p>使用獨立 topic 避免歷史回填灌入影響即時 consumer lag。
     * Partition key 同樣以 {@code assetId.toString()} 傳入。
     *
     * @param event 待發送的 PriceTickEvent（source 通常為 {@code "backfill"}），不可為 {@code null}
     * @return Kafka send future，可用來追蹤 broker ack
     * @throws NullPointerException 若 {@code event} 為 {@code null}
     */
    public CompletableFuture<SendResult<String, Object>> publishBackfillTick(PriceTickEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return kafkaTemplate.send(
                backfillTopic,
                String.valueOf(event.assetId()),
                event);
    }
}
