package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

/**
 * 持倉估值與組合摘要的 Redis 快取（TTL 60 秒）。
 *
 * <p>鍵加上 {@code v2} 版本段：Phase 04.1 改變了 {@link HoldingDto} / {@link PortfolioSummaryDto} 的
 * JSON 形狀（新增欄位、summary 金額單位改為基準幣），部署瞬間新舊程式並存時，若共用舊鍵，
 * 舊形狀 JSON 會被新程式讀成缺欄位的 DTO（或反之）。加 {@code v2} 讓新舊程式互不讀取對方寫的 JSON，
 * 舊鍵 60 秒後自然過期（RESEARCH Q8 / A4）。
 *
 * <p>持倉鍵存<strong>原幣</strong>估值，與使用者基準幣無關；summary 鍵則含基準幣
 * （鍵由版本段、userId 與基準幣代碼組成），使用者切換基準幣後直接 miss、立即重算。
 * 鍵含基準幣是因為 user 模組無法（也不應）失效 trading 的快取。交易 commit 後由
 * {@link #invalidateAfterTrade} 刪除持倉鍵與四把 summary 鍵。
 *
 * @author Yuan
 * @version 2.0
 */
@Component
public class PortfolioCache {
    private static final Logger log = LoggerFactory.getLogger(PortfolioCache.class);
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String VALUATION_PREFIX = "portfolio:valuation:v2:";
    private static final String SUMMARY_PREFIX = "portfolio:summary:v2:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PortfolioCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<HoldingDto> readHolding(Long userId, Long assetId) {
        return read(holdingKey(userId, assetId), HoldingDto.class);
    }

    /**
     * 一次 MGET 讀多筆持倉快取。
     *
     * <p>整批失敗時回空 map：呼叫端會把全部持倉當作未命中重算，頁面照常顯示而不是整頁失敗。
     *
     * @param userId   使用者 id
     * @param assetIds 標的 id 集合
     * @return 命中的標的 id → 持倉 DTO；未命中者不出現
     */
    public Map<Long, HoldingDto> readHoldings(Long userId, Collection<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(assetIds);
        List<String> keys = ids.stream().map(assetId -> holdingKey(userId, assetId)).toList();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (RuntimeException exception) {
            log.warn("Portfolio cache batch read failed for userId={}: {}", userId, exception.toString());
            return Map.of();
        }
        Map<Long, HoldingDto> hits = new LinkedHashMap<>();
        if (values == null) {
            return hits;
        }
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            String json = values.get(i);
            if (json == null) {
                continue;
            }
            try {
                hits.put(ids.get(i), objectMapper.readValue(json, HoldingDto.class));
            } catch (Exception exception) {
                log.warn("Portfolio cache entry unreadable for key={}: {}", keys.get(i), exception.toString());
            }
        }
        return hits;
    }

    public void writeHolding(Long userId, Long assetId, HoldingDto dto) {
        write(holdingKey(userId, assetId), dto);
    }

    /**
     * 讀取指定基準幣的 summary 快取。
     *
     * @param userId 使用者 id
     * @param base   使用者目前的基準幣
     * @return 命中的 summary；未命中或讀取失敗為 empty
     */
    public Optional<PortfolioSummaryDto> readSummary(Long userId, CurrencyCode base) {
        return read(summaryKey(userId, base), PortfolioSummaryDto.class);
    }

    /**
     * 寫入指定基準幣的 summary 快取。
     *
     * @param userId 使用者 id
     * @param base   summary 所使用的基準幣
     * @param dto    summary
     */
    public void writeSummary(Long userId, CurrencyCode base, PortfolioSummaryDto dto) {
        write(summaryKey(userId, base), dto);
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
     * 實際刪除持倉鍵與每個基準幣的 summary 鍵（{@code CurrencyCode.values()} 四把）。
     *
     * <p>失敗只記 WARN：快取失效不了最多是資料稍舊，不該讓已經成交的交易看起來失敗。
     *
     * @param userId  使用者 id
     * @param assetId 標的 id
     */
    private void evict(Long userId, Long assetId) {
        try {
            redisTemplate.delete(holdingKey(userId, assetId));
            for (CurrencyCode base : CurrencyCode.values()) {
                redisTemplate.delete(summaryKey(userId, base));
            }
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

    private String summaryKey(Long userId, CurrencyCode base) {
        return SUMMARY_PREFIX + userId + ":" + base.name();
    }
}
