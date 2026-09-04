package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
public class PortfolioCache {
    private static final Logger log = LoggerFactory.getLogger(PortfolioCache.class);
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String VALUATION_PREFIX = "portfolio:valuation:";
    private static final String SUMMARY_PREFIX = "portfolio:summary:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PortfolioCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<HoldingDto> readHolding(Long userId, Long assetId) {
        return read(holdingKey(userId, assetId), HoldingDto.class);
    }

    public void writeHolding(Long userId, Long assetId, HoldingDto dto) {
        write(holdingKey(userId, assetId), dto);
    }

    public Optional<PortfolioSummaryDto> readSummary(Long userId) {
        return read(summaryKey(userId), PortfolioSummaryDto.class);
    }

    public void writeSummary(Long userId, PortfolioSummaryDto dto) {
        write(summaryKey(userId), dto);
    }

    /**
     * 讓該使用者的持倉與 summary 快取失效。
     *
     * <p><strong>失效排在 COMMIT 之後</strong>，而不是呼叫當下。{@code TradingService.createTrade}
     * 帶 {@code @Transactional}，若在交易中就刪：刪掉之後、commit 之前，另一個請求會 miss → 查 DB
     * 讀到<strong>交易前</strong>的持倉 → 把舊資料寫回快取(TTL 60 秒)。成交後的 refetch 於是最長
     * 一分鐘看不到自己剛記錄的交易。排到 commit 之後，讀者查 DB 一定看得到新資料。
     *
     * <p>沒有進行中的交易時(例如單元測試或非交易路徑呼叫)則立刻失效，不會靜默漏掉。
     *
     * @param userId  使用者 id
     * @param assetId 本次交易的標的 id
     */
    public void invalidateAfterTrade(Long userId, Long assetId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(userId, assetId);
                }
            });
            return;
        }
        evict(userId, assetId);
    }

    /**
     * 實際刪除兩把快取鍵。
     *
     * <p>失敗只記 WARN：快取失效不了最多是資料稍舊，不該讓已經成交的交易看起來失敗。
     *
     * @param userId  使用者 id
     * @param assetId 標的 id
     */
    private void evict(Long userId, Long assetId) {
        try {
            redisTemplate.delete(holdingKey(userId, assetId));
            redisTemplate.delete(summaryKey(userId));
        } catch (RuntimeException exception) {
            log.warn("Portfolio cache invalidation failed for userId={}, assetId={}: {}", userId, assetId, exception.toString());
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception exception) {
            log.warn("Portfolio cache read failed for key={}: {}", key, exception.toString());
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
        } catch (Exception exception) {
            log.warn("Portfolio cache write failed for key={}: {}", key, exception.toString());
        }
    }

    private String holdingKey(Long userId, Long assetId) {
        return VALUATION_PREFIX + userId + ":" + assetId;
    }

    private String summaryKey(Long userId) {
        return SUMMARY_PREFIX + userId;
    }
}
