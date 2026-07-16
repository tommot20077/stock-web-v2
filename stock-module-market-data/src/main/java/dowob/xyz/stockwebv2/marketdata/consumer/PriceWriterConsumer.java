package dowob.xyz.stockwebv2.marketdata.consumer;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.config.KafkaConfig;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Kafka 批次消費者 — 訂閱 market-data.persist group，
 * 將 tick 與 backfill 兩條 topic 的事件批次寫入 TimescaleDB hypertable。
 *
 * <p>底層 {@code ON CONFLICT (asset_id, time) DO NOTHING} 保證 idempotency，
 * 可安全重放或重複觸發 backfill，不會產生重複資料。
 *
 * <p>批次模式（{@code batchKafkaListenerContainerFactory}）一次接收整批
 * {@link PriceTickEvent}，只需一次 JDBC round-trip 即可完成所有插入，
 * 大幅降低高頻 tick 情境下的資料庫壓力。
 *
 * @author Yuan
 * @version 1.0.0
 * @see KafkaConfig#batchKafkaListenerContainerFactory
 * @see MarketPriceRepository#insertAll(List)
 */
@Component
public class PriceWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriceWriterConsumer.class);

    private final MarketPriceRepository repository;

    /**
     * 建構子注入 {@link MarketPriceRepository}。
     *
     * @param repository 市場價格 repository，不可為 null
     */
    public PriceWriterConsumer(MarketPriceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * 批次消費 {@link PriceTickEvent}，轉換後寫入 {@code market_prices}。
     *
     * <p>同時訂閱 {@value KafkaConfig#TOPIC_PRICE_TICK} 與
     * {@value KafkaConfig#TOPIC_PRICE_BACKFILL} 兩個 topic，
     * 使用 {@code batchKafkaListenerContainerFactory} 以批次模式接收訊息。
     *
     * <p>空批次直接回傳，不發出 DB round-trip。
     *
     * @param events 一批 {@link PriceTickEvent}，由 Spring Kafka 批次交付
     */
    @KafkaListener(
            groupId = "market-data.persist",
            topics = {KafkaConfig.TOPIC_PRICE_TICK, KafkaConfig.TOPIC_PRICE_BACKFILL},
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void consume(List<PriceTickEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<MarketPrice> rows = events.stream()
                .map(e -> new MarketPrice(e.assetId(), e.occurredAt(), e.price(), e.volume()))
                .toList();
        int inserted = repository.insertAll(rows);
        log.debug("PriceWriterConsumer: received {} events, inserted {} rows", rows.size(), inserted);
    }
}
