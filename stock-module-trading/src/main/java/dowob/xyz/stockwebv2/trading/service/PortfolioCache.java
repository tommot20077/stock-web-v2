package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
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

    public void invalidateAfterTrade(Long userId, Long assetId) {
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
