package dowob.xyz.stockwebv2.marketdata.observability;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.marketdata.provider.MockDataProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MarketDataInfoContributor} 單元測試 — 驗證 /actuator/info 貢獻的 marketData 區塊。
 *
 * <p>使用 Mockito 模擬 {@link AssetFacade} 與 {@link MockDataProvider}，
 * 不需要啟動 Spring 容器。
 *
 * @author Yuan
 * @version 1.0.0
 */
class MarketDataInfoContributorTest {

    @Test
    @DisplayName("contribute — 包含 provider 名稱與 loadedAssetCount")
    void contribute_includesProviderAndAssetCount() {
        AssetFacade facade = mock(AssetFacade.class);
        when(facade.findAllTradeable()).thenReturn(List.of(
                new AssetSummary(1L, "AAPL", "Apple", AssetType.STOCK, "NASDAQ", true, true),
                new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "BINANCE", true, true)
        ));
        MockDataProvider mockProvider = mock(MockDataProvider.class);
        when(mockProvider.name()).thenReturn("mock");

        var contributor = new MarketDataInfoContributor(facade, Optional.of(mockProvider));

        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        Map<String, Object> info = builder.build().getDetails();

        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) info.get("marketData");
        assertThat(md).isNotNull();
        assertThat(md.get("provider")).isEqualTo("mock");
        assertThat(md.get("loadedAssetCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("contribute — MockDataProvider 未啟用時 provider 顯示 (none)")
    void contribute_whenNoMockProvider_showsNone() {
        AssetFacade facade = mock(AssetFacade.class);
        when(facade.findAllTradeable()).thenReturn(List.of());

        var contributor = new MarketDataInfoContributor(facade, Optional.empty());

        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        Map<String, Object> info = builder.build().getDetails();

        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) info.get("marketData");
        assertThat(md.get("provider")).isEqualTo("(none)");
        assertThat(md.get("loadedAssetCount")).isEqualTo(0);
    }
}
