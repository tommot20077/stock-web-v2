package dowob.xyz.stockwebv2.marketdata.ingest;

import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.provider.DataProvider;
import dowob.xyz.stockwebv2.marketdata.provider.PriceTick;
import dowob.xyz.stockwebv2.marketdata.provider.ProviderRegistry;
import dowob.xyz.stockwebv2.marketdata.provider.UnknownSymbolException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ScheduledIngestor} 單元測試 — 以 Mockito mock 所有外部依賴，
 * 直接呼叫 {@link ScheduledIngestor#tickAll()} 驗證行為，不依賴 Spring 排程計時。
 *
 * <p>驗證範圍：
 * <ul>
 *   <li>loadAssets 從 facade 載入正確筆數</li>
 *   <li>每次 tickAll 對每個 asset 各發布一筆 tick</li>
 *   <li>event 攜帶正確的 assetId 與 source</li>
 *   <li>個別 asset 失敗不影響其他 asset 繼續處理</li>
 *   <li>多輪呼叫累計計數正確</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class ScheduledIngestorTest {

    AssetFacade assetFacade = mock(AssetFacade.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    MarketDataIngestService ingest = mock(MarketDataIngestService.class);
    DataProvider provider = mock(DataProvider.class);

    private final List<AssetSummary> assets = List.of(
            new AssetSummary(1L, "AAPL", "Apple", AssetType.STOCK, "NASDAQ", true, true),
            new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "BINANCE", true, true),
            new AssetSummary(3L, "USD/TWD", "FX", AssetType.FX, "FX", true, true)
    );

    private ScheduledIngestor ingestor;

    @BeforeEach
    void setup() {
        when(assetFacade.findAllTradeable()).thenReturn(assets);
        when(registry.findFor(any())).thenReturn(provider);
        when(provider.name()).thenReturn("mock");
        when(provider.fetchLatest(any())).thenAnswer(inv -> {
            String sym = inv.getArgument(0);
            return new PriceTick(sym, Instant.now(), new BigDecimal("100.0"), new BigDecimal("500"));
        });
        when(ingest.publishTick(any())).thenReturn(CompletableFuture.completedFuture(null));

        ingestor = new ScheduledIngestor(assetFacade, registry, ingest);
        ingestor.loadAssets();
    }

    @Test
    void loadAssets_cachesFromFacade() {
        assertThat(ingestor.cachedAssetCount()).isEqualTo(3);
    }

    @Test
    void tickAll_publishesOneTickPerAsset() {
        ingestor.tickAll();

        verify(ingest, times(3)).publishTick(any(PriceTickEvent.class));
        assertThat(ingestor.successCount()).isEqualTo(3);
        assertThat(ingestor.failureCount()).isZero();
    }

    @Test
    void tickAll_eventCarriesAssetIdAndProviderNameAsSource() {
        ingestor.tickAll();

        var cap = org.mockito.ArgumentCaptor.forClass(PriceTickEvent.class);
        verify(ingest, times(3)).publishTick(cap.capture());
        for (PriceTickEvent event : cap.getAllValues()) {
            assertThat(event.source()).isEqualTo("mock");
            assertThat(event.assetId()).isIn(1L, 2L, 3L);
        }
    }

    @Test
    void tickAll_individualFailure_doesNotStopOtherAssets() {
        when(provider.fetchLatest("AAPL")).thenThrow(new UnknownSymbolException("AAPL"));

        ingestor.tickAll();

        verify(ingest, times(2)).publishTick(any());
        assertThat(ingestor.successCount()).isEqualTo(2);
        assertThat(ingestor.failureCount()).isEqualTo(1);
    }

    @Test
    void multipleRounds_accumulateCounts() {
        ingestor.tickAll();
        ingestor.tickAll();
        ingestor.tickAll();

        verify(ingest, times(9)).publishTick(any());
        assertThat(ingestor.successCount()).isEqualTo(9);
    }

    @Test
    void tickAll_kafkaSendAckFails_countsAsFailureNotSuccess() {
        // publishTick 同步階段不丟; future 完成時帶例外 → 應計 failureCount 而非 successCount
        CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>> failingFuture = new CompletableFuture<>();
        failingFuture.completeExceptionally(new RuntimeException("kafka broker down"));
        when(ingest.publishTick(any())).thenReturn(failingFuture);

        ingestor.tickAll();

        assertThat(ingestor.successCount()).isZero();
        assertThat(ingestor.failureCount()).isEqualTo(3);
    }
}
