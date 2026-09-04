package dowob.xyz.stockwebv2.marketdata.ingest;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;

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
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class MarketDataIngestService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 建構子注入 {@link KafkaTemplate}。
     *
     * @param kafkaTemplate Spring Boot auto-config 提供的 KafkaTemplate (JSON serde)
     */
    public MarketDataIngestService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 將即時 tick 推至 {@link KafkaConfig#TOPIC_PRICE_TICK}。
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
                KafkaConfig.TOPIC_PRICE_TICK,
                String.valueOf(event.assetId()),
                event);
    }

    /**
     * 將歷史回填 tick 推至 {@link KafkaConfig#TOPIC_PRICE_BACKFILL}。
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
                KafkaConfig.TOPIC_PRICE_BACKFILL,
                String.valueOf(event.assetId()),
                event);
    }
}
