package dowob.xyz.stockwebv2.marketdata.observability;

import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.marketdata.provider.MockDataProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 在 {@code /actuator/info} 顯示 market-data 模組執行時狀態：
 * 啟用的 DataProvider 名稱 + 載入的 active+tradeable asset 數量。
 *
 * <p>輸出範例：
 * <pre>{@code
 * {
 *   "marketData": {
 *     "provider": "mock",
 *     "loadedAssetCount": 5
 *   }
 * }
 * }</pre>
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class MarketDataInfoContributor implements InfoContributor {

    private final AssetFacade assetFacade;

    /** 可能未啟用（{@code market-data.mock.enabled=false}），使用 Optional 表達。 */
    private final Optional<MockDataProvider> mockProvider;

    /**
     * 建構 MarketDataInfoContributor。
     *
     * @param assetFacade  資產 facade，用於取得 tradeable asset 數量；不可為 null
     * @param mockProvider Optional MockDataProvider，未啟用時為 empty
     */
    public MarketDataInfoContributor(AssetFacade assetFacade,
                                     Optional<MockDataProvider> mockProvider) {
        this.assetFacade = assetFacade;
        this.mockProvider = mockProvider;
    }

    /**
     * 將 market-data 模組執行時資訊貢獻至 {@code /actuator/info}。
     *
     * @param builder info builder，不可為 null
     */
    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("marketData", Map.of(
                "provider", mockProvider.map(MockDataProvider::name).orElse("(none)"),
                "loadedAssetCount", assetFacade.findAllTradeable().size()
        ));
    }
}
