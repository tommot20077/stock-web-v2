package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link MarketLatestService} 單元測試。
 *
 * <p>使用純 Mockito，不啟動 Spring 容器，驗證：
 * <ul>
 *   <li>未知 symbol → 拋 ASSET_NOT_FOUND</li>
 *   <li>Redis hit → 直接回傳快取資料</li>
 *   <li>Redis miss → fallback DB</li>
 *   <li>Redis 與 DB 皆無 → 回 Optional.empty</li>
 *   <li>批次超過 50 → 拋 LATEST_BATCH_TOO_LARGE</li>
 *   <li>批次含未知 symbol → 略過，不報錯</li>
 *   <li>批次混合 hit/miss → 只回傳有資料的結果</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class MarketLatestServiceTest {

    AssetFacade assetFacade = mock(AssetFacade.class);
    MarketPriceRepository repository = mock(MarketPriceRepository.class);
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    ObjectMapper objectMapper = new ObjectMapper();

    MarketLatestService service;

    static final AssetSummary AAPL = new AssetSummary(1L, "AAPL", "Apple Inc.", AssetType.STOCK, "NASDAQ", true, true);
    static final AssetSummary BTC  = new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "CRYPTO", true, true);

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new MarketLatestService(assetFacade, repository, redisTemplate, objectMapper);
    }

    // ── findLatest ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLatest: 未知 symbol → 拋 ASSET_NOT_FOUND")
    void findLatest_unknownSymbol_throwsAssetNotFound() {
        when(assetFacade.findBySymbol("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findLatest("UNKNOWN"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.ASSET_NOT_FOUND));
    }

    @Test
    @DisplayName("findLatest: Redis hit → 回傳快取資料（不查 DB）")
    void findLatest_redisHit_returnsRedisData() throws Exception {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));
        String json = """
            {"symbol":"AAPL","price":"150.50","volume":"1000.00","time":"2026-01-01T00:00:00Z"}
            """.strip();
        when(valueOps.get("market:latest:1")).thenReturn(json);

        Optional<LatestPriceDto> result = service.findLatest("AAPL");

        assertThat(result).isPresent();
        LatestPriceDto dto = result.get();
        assertThat(dto.symbol()).isEqualTo("AAPL");
        assertThat(dto.price()).isEqualByComparingTo(new BigDecimal("150.50"));
        assertThat(dto.volume()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(dto.time()).isEqualTo(NOW);
        // DB 不應被呼叫
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("findLatest: Redis miss → fallback 查 DB 並回傳結果")
    void findLatest_redisMiss_fallbackToDb() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));
        when(valueOps.get("market:latest:1")).thenReturn(null);
        MarketPrice dbPrice = new MarketPrice(1L, NOW, new BigDecimal("148.00"), new BigDecimal("2000"));
        when(repository.findLatest(1L)).thenReturn(Optional.of(dbPrice));

        Optional<LatestPriceDto> result = service.findLatest("AAPL");

        assertThat(result).isPresent();
        LatestPriceDto dto = result.get();
        assertThat(dto.symbol()).isEqualTo("AAPL");
        assertThat(dto.price()).isEqualByComparingTo(new BigDecimal("148.00"));
        assertThat(dto.volume()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(dto.time()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("findLatest: Redis 與 DB 皆無資料 → Optional.empty")
    void findLatest_redisAndDbEmpty_returnsEmpty() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));
        when(valueOps.get("market:latest:1")).thenReturn(null);
        when(repository.findLatest(1L)).thenReturn(Optional.empty());

        Optional<LatestPriceDto> result = service.findLatest("AAPL");

        assertThat(result).isEmpty();
    }

    // ── findLatestBatch ────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLatestBatch: 超過 50 筆 → 拋 LATEST_BATCH_TOO_LARGE")
    void findLatestBatch_overLimit_throws() {
        List<String> tooMany = java.util.Collections.nCopies(51, "AAPL");

        assertThatThrownBy(() -> service.findLatestBatch(tooMany))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.LATEST_BATCH_TOO_LARGE));
    }

    @Test
    @DisplayName("findLatestBatch: 含未知 symbol → 略過，不報錯")
    void findLatestBatch_unknownSymbolsSkipped() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));
        when(assetFacade.findBySymbol("UNKNOWN")).thenReturn(Optional.empty());
        when(valueOps.get("market:latest:1")).thenReturn(null);
        when(repository.findLatest(1L)).thenReturn(Optional.empty());

        List<LatestPriceDto> result = service.findLatestBatch(List.of("AAPL", "UNKNOWN"));

        // 兩者皆無資料（AAPL DB empty, UNKNOWN skipped），回空 list 不報錯
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findLatestBatch: 混合 Redis hit/miss/unknown → 只回有資料的結果")
    void findLatestBatch_mixedHitMiss_returnsHitsOnly() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));
        when(assetFacade.findBySymbol("BTC")).thenReturn(Optional.of(BTC));
        when(assetFacade.findBySymbol("UNKNOWN")).thenReturn(Optional.empty());

        // AAPL: Redis hit
        String aaplJson = """
            {"symbol":"AAPL","price":"150.00","volume":"500.00","time":"2026-01-01T00:00:00Z"}
            """.strip();
        when(valueOps.get("market:latest:1")).thenReturn(aaplJson);

        // BTC: Redis miss, DB empty
        when(valueOps.get("market:latest:2")).thenReturn(null);
        when(repository.findLatest(2L)).thenReturn(Optional.empty());

        List<LatestPriceDto> result = service.findLatestBatch(List.of("AAPL", "BTC", "UNKNOWN"));

        // 只有 AAPL 有結果
        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).price()).isEqualByComparingTo(new BigDecimal("150.00"));
    }
}
