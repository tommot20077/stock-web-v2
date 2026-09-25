package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.trading.api.HoldingDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link PortfolioCache} 的失效時機。
 *
 * <p>本檔存在的理由是一個實際的競態：{@code TradingService.createTrade} 帶 {@code @Transactional}，
 * 而快取失效原本在方法體內、也就是<strong>在 COMMIT 之前</strong>執行。於是：
 *
 * <ol>
 *   <li>下單交易刪掉快取，但尚未 commit；</li>
 *   <li>另一個請求讀快取 miss → 查 DB，讀到的是<strong>交易前</strong>的持倉；</li>
 *   <li>它把這份舊資料寫回快取，TTL 60 秒；</li>
 *   <li>下單交易 commit。</li>
 * </ol>
 *
 * <p>結果是成交後的 refetch 最長一分鐘看不到自己剛記錄的交易 —— 正好打中 Phase 4 的
 * post-trade refetch。失效必須排到 commit 之後。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("portfolio 快取失效時機")
class PortfolioCacheTest {

    private StringRedisTemplate redisTemplate;
    private PortfolioCache cache;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        cache = new PortfolioCache(redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("交易進行中不立刻刪快取，等 COMMIT 之後才刪")
    void defersInvalidationUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateAfterTrade(7L, 42L);

        verify(redisTemplate, never()).delete("portfolio:valuation:7:42");
        verify(redisTemplate, never()).delete("portfolio:summary:7");

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(redisTemplate).delete("portfolio:valuation:7:42");
        verify(redisTemplate).delete("portfolio:summary:7");
    }

    @Test
    @DisplayName("沒有進行中的交易時立刻刪，不會靜默漏掉失效")
    void invalidatesImmediatelyWithoutTransaction() {
        cache.invalidateAfterTrade(7L, 42L);

        verify(redisTemplate).delete("portfolio:valuation:7:42");
        verify(redisTemplate).delete("portfolio:summary:7");
    }

    @Test
    @DisplayName("批次讀取持倉快取：一次 MGET，只回傳命中的項目")
    @SuppressWarnings("unchecked")
    void readHoldingsUsesSingleMultiGet() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(List.of("portfolio:valuation:7:41", "portfolio:valuation:7:42"))).thenReturn(Arrays.asList(
            null,
            "{\"assetId\":\"u\",\"symbol\":\"MSFT\",\"assetName\":\"MSFT\",\"totalQuantity\":5,\"avgCost\":200,"
                + "\"costBasis\":1000,\"marketPrice\":210,\"marketValue\":1050,\"realizedPnl\":0,\"unrealizedPnl\":50,"
                + "\"roi\":0.05,\"priceTime\":null,\"lastUpdated\":null}"));

        Map<Long, HoldingDto> cached = cache.readHoldings(7L, List.of(41L, 42L));

        assertThat(cached).containsOnlyKeys(42L);
        assertThat(cached.get(42L).symbol()).isEqualTo("MSFT");
        verify(valueOps, times(1)).multiGet(List.of("portfolio:valuation:7:41", "portfolio:valuation:7:42"));
    }

    @Test
    @DisplayName("批次讀取持倉快取：Redis 失敗時回空，讓呼叫端全部重算而不是整頁失敗")
    @SuppressWarnings("unchecked")
    void readHoldingsReturnsEmptyWhenRedisFails() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(org.mockito.ArgumentMatchers.anyList())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.readHoldings(7L, List.of(41L))).isEmpty();
    }
}
