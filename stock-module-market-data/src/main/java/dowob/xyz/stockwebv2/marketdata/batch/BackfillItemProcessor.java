package dowob.xyz.stockwebv2.marketdata.batch;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.event.PriceTickEvent;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.provider.PriceTick;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 將 provider 回傳的 {@link PriceTick} 轉成可寫入 Kafka 的 {@link PriceTickEvent}，
 * source 標記為 {@value #SOURCE}。
 *
 * <p>為正確保留歷史 tick 的時間戳，使用直接建構子（{@code new PriceTickEvent(...)}）
 * 而非 {@link PriceTickEvent#of}，因為後者會以 {@code Instant.now()} 覆寫 {@code occurredAt}。
 *
 * <p>Asset 查詢採 lazy initialization，每次 StepScope 生命週期內只查詢一次。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
@StepScope
public class BackfillItemProcessor implements ItemProcessor<PriceTick, PriceTickEvent> {

    /** backfill 事件的來源標記。 */
    public static final String SOURCE = "backfill";

    private final AssetFacade assetFacade;
    private final String symbol;

    /** 快取的 asset 元資料；第一次 process 時才初始化（Lazy）。 */
    private AssetSummary asset;

    /**
     * 建構子注入 AssetFacade 與 Job Parameter symbol。
     *
     * @param assetFacade asset 元資料 facade，不可為 null
     * @param symbol      Job Parameter {@code symbol}，目標資產代號
     */
    public BackfillItemProcessor(AssetFacade assetFacade,
                                 @Value("#{jobParameters['symbol']}") String symbol) {
        this.assetFacade = assetFacade;
        this.symbol = symbol;
    }

    /**
     * 將 {@link PriceTick} 轉換為 {@link PriceTickEvent}。
     *
     * <p>保留原始 {@code tick.time()} 作為 {@code occurredAt}，使 DB 記錄的時間
     * 與實際歷史時間一致。
     *
     * @param tick 來自 DataProvider 的原始 tick
     * @return 對應的 PriceTickEvent，source 為 {@value #SOURCE}
     * @throws BusinessException 若找不到指定 symbol 的 asset
     */
    @Override
    public PriceTickEvent process(PriceTick tick) {
        if (asset == null) {
            asset = assetFacade.findBySymbol(symbol)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.ASSET_NOT_FOUND, "Asset not found: " + symbol));
        }
        return new PriceTickEvent(
                UUID.randomUUID().toString(),
                tick.time(),           // 保留原始歷史時間，不使用 Instant.now()
                asset.id(),
                tick.symbol(),
                tick.price(),
                tick.volume(),
                SOURCE
        );
    }
}
