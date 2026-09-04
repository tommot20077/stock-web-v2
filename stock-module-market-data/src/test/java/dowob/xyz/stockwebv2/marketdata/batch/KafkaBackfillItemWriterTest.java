package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.marketdata.ingest.MarketDataIngestService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KafkaBackfillItemWriter} 單元測試,驗證 chunk 內 send future 等待語意。
 *
 * @author Yuan
 * @version 1.0.0
 */
class KafkaBackfillItemWriterTest {

    private final MarketDataIngestService ingest = mock(MarketDataIngestService.class);
    private final KafkaBackfillItemWriter writer = new KafkaBackfillItemWriter(ingest);

    private PriceTickEvent sampleEvent(long assetId, String symbol) {
        return PriceTickEvent.of(assetId, symbol, new BigDecimal("100"), new BigDecimal("10"), "backfill");
    }

    @Test
    @DisplayName("write 空 chunk → no-op 不呼叫 ingest")
    void write_emptyChunk_noOp() {
        writer.write(new Chunk<>(List.of()));
        verify(ingest, never()).publishBackfillTick(any());
    }

    @Test
    @DisplayName("write 多筆全部 ack 成功 → 正常返回,每筆都呼叫 publishBackfillTick")
    void write_allSuccess_returnsNormally() {
        when(ingest.publishBackfillTick(any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        writer.write(new Chunk<>(List.of(
            sampleEvent(1L, "AAPL"),
            sampleEvent(2L, "BTC"),
            sampleEvent(3L, "ETH")
        )));

        verify(ingest, times(3)).publishBackfillTick(any());
    }

    @Test
    @DisplayName("write 任一 future 失敗 → 拋 KafkaBackfillSendException 包覆原因")
    void write_anySendFails_throwsWrapped() {
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        CompletableFuture<SendResult<String, Object>> bad = new CompletableFuture<>();
        bad.completeExceptionally(new RuntimeException("simulated kafka failure"));

        when(ingest.publishBackfillTick(any()))
            .thenReturn(ok)
            .thenReturn(bad)
            .thenReturn(ok);

        assertThatThrownBy(() -> writer.write(new Chunk<>(List.of(
            sampleEvent(1L, "AAPL"),
            sampleEvent(2L, "BTC"),
            sampleEvent(3L, "ETH")
        ))))
            .isInstanceOf(KafkaBackfillItemWriter.KafkaBackfillSendException.class)
            .hasMessageContaining("chunk size=3")
            .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("write 等候被 interrupt → 復原 interrupt flag 並拋 KafkaBackfillSendException")
    void write_interrupted_restoresFlagAndThrows() {
        CompletableFuture<SendResult<String, Object>> pending = new CompletableFuture<>();
        when(ingest.publishBackfillTick(any())).thenReturn(pending);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> writer.write(new Chunk<>(List.of(sampleEvent(1L, "AAPL")))))
                .isInstanceOf(KafkaBackfillItemWriter.KafkaBackfillSendException.class)
                .hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // clear the interrupted state so subsequent tests aren't affected
            Thread.interrupted();
        }
    }
}
