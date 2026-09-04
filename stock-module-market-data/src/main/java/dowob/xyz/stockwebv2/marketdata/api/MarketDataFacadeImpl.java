package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link MarketDataFacade} 的實作 —— 以 assetId 取最新成交價。
 *
 * <p>來源順序與 {@code MarketLatestService} 一致：Redis {@code market:latest:{assetId}}
 * （由 {@code WsBroadcastConsumer} 每筆 tick 寫入，TTL 5 分鐘）→ {@code market_prices} 最新一列。
 * 差別在於本類別<strong>以 assetId 為鍵</strong>，呼叫端（trading）已經有 assetId，不必再繞一次
 * symbol → asset 的查詢。
 *
 * <p>Redis 讀取失敗一律降級到 DB 而不是往外丟：行情快取不可用時，持倉頁應該顯示稍舊的價格，
 * 而不是整頁失敗。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class MarketDataFacadeImpl implements MarketDataFacade {

    private static final Logger log = LoggerFactory.getLogger(MarketDataFacadeImpl.class);

    /** Redis latest cache key 前綴，與 {@code WsBroadcastConsumer} 寫入端一致。 */
    private static final String REDIS_LATEST_KEY = "market:latest:";

    private final StringRedisTemplate redisTemplate;
    private final MarketPriceRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 建構子注入。
     *
     * @param redisTemplate Redis 操作模板，不可為 null
     * @param repository    market_prices 儲存庫，不可為 null
     * @param objectMapper  JSON 反序列化器，不可為 null
     */
    public MarketDataFacadeImpl(StringRedisTemplate redisTemplate,
                                MarketPriceRepository repository,
                                ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Optional<LatestMarketPrice> findLatestPrice(Long assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        return readFromCache(assetId).or(() -> readFromDatabase(assetId));
    }

    /**
     * 讀 Redis latest cache。
     *
     * @param assetId 資產 id
     * @return 快取命中的最新價；miss 或讀取失敗時 {@link Optional#empty()}
     */
    private Optional<LatestMarketPrice> readFromCache(Long assetId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_LATEST_KEY + assetId);
            if (json == null) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = objectMapper.readValue(json, Map.class);
            Object price = cached.get("price");
            Object time = cached.get("time");
            if (price == null || time == null) {
                return Optional.empty();
            }
            return Optional.of(new LatestMarketPrice(
                new BigDecimal(price.toString()),
                toOffsetDateTime(Instant.parse(time.toString()))));
        } catch (Exception ex) {
            log.warn("Redis latest cache read failed for assetId={}: {}", assetId, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 查 {@code market_prices} 最新一列。
     *
     * @param assetId 資產 id
     * @return 最新價；該資產從未有過 tick 時 {@link Optional#empty()}
     */
    private Optional<LatestMarketPrice> readFromDatabase(Long assetId) {
        return repository.findLatest(assetId)
            .map(price -> new LatestMarketPrice(price.price(), toOffsetDateTime(price.time())));
    }

    /**
     * @param instant market-data 內部的時間表示
     * @return UTC 偏移量的 {@link OffsetDateTime}，對齊 API 層 DTO
     */
    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
