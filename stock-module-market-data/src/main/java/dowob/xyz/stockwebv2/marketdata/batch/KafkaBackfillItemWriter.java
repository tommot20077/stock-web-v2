package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.ingest.MarketDataIngestService;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Spring Batch ItemWriter — 把每筆 {@link PriceTickEvent} 透過
 * {@link MarketDataIngestService} 推到 backfill topic。
 *
 * <p>下游 {@code PriceWriterConsumer} 訂閱 backfill topic，
 * 與即時 tick 共用相同寫入路徑（{@code market_prices} hypertable）。
 *
 * <p>Chunk 內的每筆事件分別呼叫 {@code publishBackfillTick}，
 * Kafka producer 自帶非同步批次傳送，不需手動批量。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class KafkaBackfillItemWriter implements ItemWriter<PriceTickEvent> {

    private final MarketDataIngestService ingestService;

    /**
     * 建構子注入 {@link MarketDataIngestService}。
     *
     * @param ingestService market data 推播服務，不可為 null
     */
    public KafkaBackfillItemWriter(MarketDataIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * 將整個 chunk 的 {@link PriceTickEvent} 逐筆推至 backfill topic。
     *
     * @param chunk Spring Batch 交付的一批事件
     */
    @Override
    public void write(Chunk<? extends PriceTickEvent> chunk) {
        for (PriceTickEvent event : chunk.getItems()) {
            ingestService.publishBackfillTick(event);
        }
    }
}
