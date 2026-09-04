package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import java.util.List;
import java.util.Optional;

/**
 * {@link MockDataProvider} 的 DataProvider 契約測試。
 *
 * <p>繼承 {@link DataProviderContractTest} 的六項契約，透過 stubbed {@link AssetFacade}
 * 在不啟動 Spring 容器的情況下驗證 mock provider 的行為一致性。
 *
 * @author Yuan
 * @version 1.0.0
 */
class MockDataProviderContractTest extends DataProviderContractTest {

    /** 測試用假資產清單，涵蓋四種 AssetType。 */
    private static final List<AssetSummary> FAKE_ASSETS = List.of(
            new AssetSummary(1L, "AAPL", "Apple Inc", AssetType.STOCK, "NASDAQ", true, true),
            new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "BINANCE", true, true),
            new AssetSummary(3L, "USD/TWD", "USD to TWD", AssetType.FX, "FX", true, true),
            new AssetSummary(4L, "US10Y", "US 10-Year Treasury", AssetType.BOND, "BOND", true, true)
    );

    private final MockDataProvider provider;

    MockDataProviderContractTest() {
        AssetFacade fake = new FakeAssetFacade(FAKE_ASSETS);
        this.provider = new MockDataProvider(fake);
        this.provider.initialize(); // 手動呼叫 @PostConstruct，因為未啟動 Spring 容器
    }

    @Override
    protected DataProvider provider() {
        return provider;
    }

    @Override
    protected String knownSymbol() {
        return "AAPL";
    }

    // ── 輔助 fake ──────────────────────────────────────────────────────────────

    /**
     * 測試用的 AssetFacade stub，直接回傳預設資產清單，不依賴資料庫。
     */
    static class FakeAssetFacade implements AssetFacade {

        private final List<AssetSummary> all;

        FakeAssetFacade(List<AssetSummary> all) {
            this.all = all;
        }

        @Override
        public List<AssetSummary> findAllTradeable() {
            return all;
        }

        @Override
        public Optional<AssetSummary> findBySymbol(String s) {
            return all.stream().filter(a -> a.symbol().equals(s)).findFirst();
        }
    }
}
