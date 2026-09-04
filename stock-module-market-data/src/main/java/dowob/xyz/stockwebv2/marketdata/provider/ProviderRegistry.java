package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

/**
 * 決定特定 asset 該由哪個 DataProvider 服務。Mock 階段只有單一 provider,
 * 未來接真實 provider 時可基於 asset.market 路由到不同 provider。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface ProviderRegistry {

    /**
     * 取得指定 asset 對應的 DataProvider。
     *
     * @param asset asset metadata，不可為 null
     * @return 對應的 DataProvider，絕不為 null
     * @throws NullPointerException  若 {@code asset} 為 null
     * @throws IllegalStateException 若無 provider 可服務該 asset（Mock 階段不會發生）
     */
    DataProvider findFor(AssetSummary asset);
}
