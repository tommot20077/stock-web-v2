package dowob.xyz.stockwebv2.trading.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
}
