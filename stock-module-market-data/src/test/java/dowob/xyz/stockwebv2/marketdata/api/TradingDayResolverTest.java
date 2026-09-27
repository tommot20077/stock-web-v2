package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TradingDayResolver} 單元測試。
 *
 * @author Yuan
 * @version 1.0.0
 */
class TradingDayResolverTest {

    private final AssetFacade assetFacade = mock(AssetFacade.class);
    private final TradingDayResolver resolver = new TradingDayResolver(assetFacade);

    @Test
    @DisplayName("依資產的 assetType / market 決定交易日,且同一 symbol 只查一次")
    void resolvesFromAssetAndCaches() {
        when(assetFacade.findBySymbol("2330.TW")).thenReturn(Optional.of(
            new AssetSummary(5L, "2330.TW", "TSMC", AssetType.STOCK, "TW", true, true)));

        assertThat(resolver.forSymbol("2330.TW").zone()).isEqualTo(ZoneId.of("Asia/Taipei"));
        assertThat(resolver.forSymbol("2330.TW").zone()).isEqualTo(ZoneId.of("Asia/Taipei"));

        // 每筆 tick 都會問一次,不能每次都打資料庫
        verify(assetFacade, times(1)).findBySymbol("2330.TW");
    }

    @Test
    @DisplayName("查不到的 symbol → UTC,且不快取(之後新增的資產仍能被正確解析)")
    void unknownSymbol_fallsBackToUtcWithoutCaching() {
        when(assetFacade.findBySymbol("NEW")).thenReturn(Optional.empty());

        assertThat(resolver.forSymbol("NEW")).isEqualTo(TradingDay.UTC);
        resolver.forSymbol("NEW");

        verify(assetFacade, times(2)).findBySymbol("NEW");
    }
}
