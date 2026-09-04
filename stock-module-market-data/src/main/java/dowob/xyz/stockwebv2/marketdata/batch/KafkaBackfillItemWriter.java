package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.ingest.MarketDataIngestService;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring Batch ItemWriter — 把每筆 {@link PriceTickEvent} 透過
 * {@link MarketDataIngestService} 推到 backfill topic。
 *
 * <p>下游 {@code PriceWriterConsumer} 訂閱 backfill topic，
 * 與即時 tick 共用相同寫入路徑（{@code market_prices} hypertable）。
 *
 * <p>Chunk 內的每筆事件分別呼叫 {@code publishBackfillTick},Kafka producer 非同步傳送;
 * 為避免 chunk 在 send 還沒 ack 就被標記 COMPLETED 導致 silent data loss,在 {@link #write}
 * 結束前等待 chunk 內所有 future ack。任一筆 send 失敗會 propagate exception 讓 Spring Batch
 * 將該 chunk 標記為 FAILED,啟用 retry/recovery 機制。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class KafkaBackfillItemWriter implements ItemWriter<PriceTickEvent> {

    private static final Duration SEND_AWAIT_TIMEOUT = Duration.ofSeconds(30);

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
     * 將整個 chunk 的 {@link PriceTickEvent} 推至 backfill topic 並阻塞等待所有 send ack。
     *
     * <p>實作步驟:
     * <ol>
     *   <li>對 chunk 每筆呼叫 {@link MarketDataIngestService#publishBackfillTick},
     *       收集回傳的 {@link CompletableFuture}</li>
     *   <li>{@link CompletableFuture#allOf} 等待全部 ack,最長 {@value #SEND_AWAIT_TIMEOUT}</li>
     *   <li>任一 future 失敗或 timeout → 以 {@link KafkaBackfillSendException} 包覆並拋出,
     *       Spring Batch 標記此 chunk 為 FAILED</li>
     * </ol>
     *
     * @param chunk Spring Batch 交付的一批事件
     * @throws KafkaBackfillSendException 任一筆 send 失敗或整批 ack timeout
     */
    @Override
    public void write(Chunk<? extends PriceTickEvent> chunk) {
        List<? extends PriceTickEvent> items = chunk.getItems();
        if (items.isEmpty()) {
            return;
        }
        List<CompletableFuture<SendResult<String, Object>>> futures = new ArrayList<>(items.size());
        for (PriceTickEvent event : items) {
            futures.add(ingestService.publishBackfillTick(event));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(SEND_AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KafkaBackfillSendException(
                "Interrupted while awaiting Kafka backfill acks for chunk size=" + items.size(), ie);
        } catch (ExecutionException ee) {
            throw new KafkaBackfillSendException(
                "Kafka backfill send failed for chunk size=" + items.size(), ee.getCause());
        } catch (TimeoutException te) {
            throw new KafkaBackfillSendException(
                "Kafka backfill send timed out (" + SEND_AWAIT_TIMEOUT
                    + ") for chunk size=" + items.size(), te);
        }
    }

    /** chunk 內任何 send 失敗的 wrapper exception,Spring Batch 會將 chunk 標 FAILED。 */
    public static class KafkaBackfillSendException extends RuntimeException {
        public KafkaBackfillSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
