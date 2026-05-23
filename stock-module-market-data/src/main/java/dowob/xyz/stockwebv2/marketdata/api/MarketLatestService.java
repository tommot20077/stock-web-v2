package dowob.xyz.stockwebv2.marketdata.api;

import tools.jackson.databind.ObjectMapper;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.persistence.MarketPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提供 REST {@code /latest} 路徑使用的查詢邏輯：Redis-first，miss 時 fallback DB。
 *
 * <p>Redis key 格式：{@code market:latest:{assetId}}，由 T22 WsBroadcastConsumer 寫入。
 * 快取 JSON 格式：{@code { "symbol", "price", "volume", "time" }}。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class MarketLatestService {

    private static final Logger log = LoggerFactory.getLogger(MarketLatestService.class);
    private static final String REDIS_LATEST_KEY = "market:latest:";

    private final AssetFacade assetFacade;
    private final MarketPriceRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 建構子注入所有依賴。
     *
     * @param assetFacade   資產 facade，用於解析 symbol → assetId
     * @param repository    市場價格 repository，fallback 查詢用
     * @param redisTemplate Redis 客戶端，讀取最新 tick 快取
     * @param objectMapper  JSON 反序列化器
     */
    public MarketLatestService(AssetFacade assetFacade,
                               MarketPriceRepository repository,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.assetFacade = assetFacade;
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 取得單一資產最新 tick。Redis miss 則 fallback 查 DB；兩者皆無回 {@link Optional#empty()}。
     *
     * @param symbol 資產代號，大小寫敏感
     * @return 最新 tick DTO
     * @throws BusinessException 若 symbol 不存在（{@link ErrorCode#ASSET_NOT_FOUND}）
     */
    public Optional<LatestPriceDto> findLatest(String symbol) {
        AssetSummary asset = assetFacade.findBySymbol(symbol)
            .orElseThrow(() -> new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found: " + symbol));
        return findLatestForAsset(asset);
    }

    /**
     * 批次取多個資產最新 tick。未知 symbol 或無資料的 asset 直接略過（不報錯）。
     * 超過 50 筆拋 {@link ErrorCode#LATEST_BATCH_TOO_LARGE}。
     *
     * @param symbols 資產代號清單，不可超過 50
     * @return 有資料的 tick DTO 清單（順序與輸入一致）
     * @throws BusinessException 若 symbols 超過 50 筆
     */
    public List<LatestPriceDto> findLatestBatch(List<String> symbols) {
        if (symbols.size() > 50) {
            throw new BusinessException(ErrorCode.LATEST_BATCH_TOO_LARGE,
                "Batch size " + symbols.size() + " exceeds limit 50");
        }
        List<LatestPriceDto> result = new ArrayList<>();
        for (String symbol : symbols) {
            assetFacade.findBySymbol(symbol).ifPresent(asset ->
                findLatestForAsset(asset).ifPresent(result::add));
        }
        return result;
    }

    /**
     * 對單一 asset 執行 Redis-first 查詢，miss 時 fallback DB。
     *
     * @param asset 已解析的 asset summary
     * @return 最新 tick DTO；無資料時 {@link Optional#empty()}
     */
    private Optional<LatestPriceDto> findLatestForAsset(AssetSummary asset) {
        // 1) Redis cache
        try {
            String json = redisTemplate.opsForValue().get(REDIS_LATEST_KEY + asset.id());
            if (json != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = objectMapper.readValue(json, Map.class);
                String priceStr  = (String) m.get("price");
                String volumeStr = (String) m.get("volume");
                String timeStr   = (String) m.get("time");
                return Optional.of(new LatestPriceDto(
                    asset.symbol(),
                    new BigDecimal(priceStr),
                    volumeStr == null ? null : new BigDecimal(volumeStr),
                    Instant.parse(timeStr)
                ));
            }
        } catch (Exception ex) {
            log.warn("Redis latest cache read failed for assetId={}: {}", asset.id(), ex.toString());
            // fall through to DB
        }

        // 2) DB fallback
        return repository.findLatest(asset.id())
            .map(mp -> new LatestPriceDto(asset.symbol(), mp.price(), mp.volume(), mp.time()));
    }
}
