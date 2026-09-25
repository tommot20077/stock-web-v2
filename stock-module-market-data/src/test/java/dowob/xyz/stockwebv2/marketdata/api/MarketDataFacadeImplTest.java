package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPrice;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link MarketDataFacadeImpl} 單元測試 —— 取價的來源順序與降級行為。
 *
 * <p>快取 JSON 的欄位形狀刻意與寫入端 {@code WsBroadcastConsumer.buildTickData} 逐字對齊
 * （{@code price} / {@code volume} / {@code time} 皆為字串）。兩邊若漂移，這裡會先紅。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("market-data facade 取價")
class MarketDataFacadeImplTest {

    private static final Long ASSET_ID = 42L;
    private static final String CACHE_KEY = "market:latest:42";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MarketPriceRepository repository;
    private MarketDataFacadeImpl facade;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        repository = mock(MarketPriceRepository.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        facade = new MarketDataFacadeImpl(redisTemplate, repository, new ObjectMapper());
    }

    @Test
    @DisplayName("Redis 命中時直接回傳快取價，不查資料庫")
    void returnsCachedPriceWithoutTouchingDatabase() {
        when(valueOps.get(CACHE_KEY)).thenReturn(
            "{\"symbol\":\"AAPL\",\"price\":\"218.40000000\",\"volume\":\"1000\",\"time\":\"2026-09-04T02:00:00Z\"}");

        Optional<LatestMarketPrice> latest = facade.findLatestPrice(ASSET_ID);

        assertThat(latest).isPresent();
        assertThat(latest.get().price()).isEqualByComparingTo("218.40000000");
        assertThat(latest.get().priceTime()).isEqualTo(OffsetDateTime.parse("2026-09-04T02:00:00Z"));
        verify(repository, never()).findLatest(ASSET_ID);
    }

    @Test
    @DisplayName("Redis miss 時退回 market_prices 最新一列")
    void fallsBackToDatabaseOnCacheMiss() {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(repository.findLatest(ASSET_ID)).thenReturn(Optional.of(new MarketPrice(
            ASSET_ID, Instant.parse("2026-09-03T12:00:00Z"), new BigDecimal("199.5"), BigDecimal.TEN)));

        Optional<LatestMarketPrice> latest = facade.findLatestPrice(ASSET_ID);

        assertThat(latest).isPresent();
        assertThat(latest.get().price()).isEqualByComparingTo("199.5");
        assertThat(latest.get().priceTime()).isEqualTo(OffsetDateTime.parse("2026-09-03T12:00:00Z"));
    }

    @Test
    @DisplayName("Redis 讀取失敗時降級查資料庫，不把例外往外丟")
    void degradesToDatabaseWhenCacheReadFails() {
        /*
         * 行情快取不可用時，持倉頁應該顯示稍舊的價格，而不是整頁失敗。
         */
        when(valueOps.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(repository.findLatest(ASSET_ID)).thenReturn(Optional.of(new MarketPrice(
            ASSET_ID, Instant.parse("2026-09-03T12:00:00Z"), new BigDecimal("199.5"), null)));

        Optional<LatestMarketPrice> latest = facade.findLatestPrice(ASSET_ID);

        assertThat(latest).isPresent();
        assertThat(latest.get().price()).isEqualByComparingTo("199.5");
    }

    @Test
    @DisplayName("快取與資料庫都沒有時回 empty，不塞任何預設價")
    void returnsEmptyWhenNoPriceAnywhere() {
        /*
         * 「沒有行情」與「行情等於某個數字」必須可區分：回 empty，讓呼叫端自己決定退場方式。
         */
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(repository.findLatest(ASSET_ID)).thenReturn(Optional.empty());

        assertThat(facade.findLatestPrice(ASSET_ID)).isEmpty();
    }

    @Test
    @DisplayName("快取內容缺欄位時視為 miss，退回資料庫而不是丟出空指標")
    void treatsMalformedCacheEntryAsMiss() {
        when(valueOps.get(CACHE_KEY)).thenReturn("{\"symbol\":\"AAPL\"}");
        when(repository.findLatest(ASSET_ID)).thenReturn(Optional.of(new MarketPrice(
            ASSET_ID, Instant.parse("2026-09-03T12:00:00Z"), new BigDecimal("199.5"), null)));

        assertThat(facade.findLatestPrice(ASSET_ID)).isPresent();
        verify(repository).findLatest(ASSET_ID);
    }

    @Test
    @DisplayName("批次取價：一次 MGET 取快取，未命中者以一次 SQL 補齊")
    void batchUsesOneMultiGetAndOneQueryForMisses() {
        when(valueOps.multiGet(List.of("market:latest:41", "market:latest:42"))).thenReturn(Arrays.asList(
            "{\"symbol\":\"AAPL\",\"price\":\"150\",\"volume\":\"1\",\"time\":\"2026-09-04T02:00:00Z\"}",
            null));
        when(repository.findLatestBatch(List.of(42L))).thenReturn(List.of(new MarketPrice(
            42L, Instant.parse("2026-09-03T12:00:00Z"), new BigDecimal("210"), null)));

        Map<Long, LatestMarketPrice> prices = facade.findLatestPrices(List.of(41L, 42L));

        assertThat(prices).containsOnlyKeys(41L, 42L);
        assertThat(prices.get(41L).price()).isEqualByComparingTo("150");
        assertThat(prices.get(42L).price()).isEqualByComparingTo("210");
        verify(valueOps, times(1)).multiGet(anyList());
        verify(repository, times(1)).findLatestBatch(List.of(42L));
        verify(repository, never()).findLatest(anyLong());
    }

    @Test
    @DisplayName("批次取價：全部命中快取時不查資料庫")
    void batchSkipsDatabaseWhenAllCached() {
        when(valueOps.multiGet(List.of("market:latest:41"))).thenReturn(List.of(
            "{\"symbol\":\"AAPL\",\"price\":\"150\",\"volume\":\"1\",\"time\":\"2026-09-04T02:00:00Z\"}"));

        assertThat(facade.findLatestPrices(List.of(41L))).containsOnlyKeys(41L);
        verify(repository, never()).findLatestBatch(anyList());
    }

    @Test
    @DisplayName("批次取價：Redis 失敗時整批降級查資料庫；查無行情者不出現在結果中")
    void batchDegradesToDatabaseAndOmitsUnknownAssets() {
        when(valueOps.multiGet(anyList())).thenThrow(new IllegalStateException("redis down"));
        when(repository.findLatestBatch(List.of(41L, 42L))).thenReturn(List.of(new MarketPrice(
            41L, Instant.parse("2026-09-03T12:00:00Z"), new BigDecimal("150"), null)));

        Map<Long, LatestMarketPrice> prices = facade.findLatestPrices(List.of(41L, 42L));

        assertThat(prices).containsOnlyKeys(41L);
    }

    @Test
    @DisplayName("批次取價：空輸入直接回空，不碰 Redis 也不碰資料庫")
    void batchWithNoAssetsDoesNothing() {
        assertThat(facade.findLatestPrices(List.of())).isEmpty();
        verify(redisTemplate, never()).opsForValue();
        verify(repository, never()).findLatestBatch(anyList());
    }
}
