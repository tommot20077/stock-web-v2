package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MockOnlyProviderRegistry} 單元測試。
 *
 * <p>驗證 Mock 階段 registry 行為：
 * <ul>
 *   <li>任意 asset（不論 AssetType）一律回傳同一個 mockProvider 實例</li>
 *   <li>回傳的 provider name 為 {@code "mock"}</li>
 *   <li>傳入 null asset 拋出 {@link NullPointerException}</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class MockOnlyProviderRegistryTest {

    private MockOnlyProviderRegistry registry;
    private MockDataProvider mock;

    @BeforeEach
    void setup() {
        var fake = new MockDataProviderContractTest.FakeAssetFacade(List.of(
                new AssetSummary(1L, "AAPL", "Apple", AssetType.STOCK, "NASDAQ", true, true),
                new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "BINANCE", true, true)
        ));
        mock = new MockDataProvider(fake);
        mock.initialize();
        registry = new MockOnlyProviderRegistry(mock);
    }

    @Test
    @DisplayName("findFor()：任意 AssetType 的 asset 皆回傳同一個 mockProvider 實例")
    void findFor_anyAsset_returnsMockProvider() {
        var aapl = new AssetSummary(1L, "AAPL", "Apple", AssetType.STOCK, "NASDAQ", true, true);
        var btc = new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "BINANCE", true, true);
        var fx = new AssetSummary(3L, "USD/TWD", "FX", AssetType.FX, "FX", true, true);

        assertThat(registry.findFor(aapl)).isSameAs(mock);
        assertThat(registry.findFor(btc)).isSameAs(mock);
        assertThat(registry.findFor(fx)).isSameAs(mock);
    }

    @Test
    @DisplayName("findFor()：回傳的 provider name 為 \"mock\"")
    void findFor_returnedProvider_nameIsMock() {
        var aapl = new AssetSummary(1L, "AAPL", "Apple", AssetType.STOCK, "NASDAQ", true, true);

        assertThat(registry.findFor(aapl).name()).isEqualTo("mock");
    }

    @Test
    @DisplayName("findFor()：傳入 null asset → 拋出 NullPointerException")
    void findFor_nullAsset_throwsNullPointerException() {
        assertThatThrownBy(() -> registry.findFor(null))
                .isInstanceOf(NullPointerException.class);
    }
}
