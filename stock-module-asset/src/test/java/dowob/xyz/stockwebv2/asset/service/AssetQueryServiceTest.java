package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.repository.AssetRepository;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AssetQueryService} 單元測試:報價欄位一律來自 market-data,不再讀種子表。
 *
 * @author Yuan
 * @version 1.0.0
 */
class AssetQueryServiceTest {

    private final AssetRepository repository = mock(AssetRepository.class);
    private final MarketDataFacade marketData = mock(MarketDataFacade.class);
    private final AssetQueryService service = new AssetQueryService(repository, marketData);

    private static Asset asset(long id, String symbol, AssetType type, String market) {
        return new Asset(id, UUID.randomUUID(), symbol, symbol, type, market, CurrencyCode.USD, null, true, true);
    }

    @Test
    @DisplayName("漲跌 = 最新價 − 前收;漲跌% 取兩位;高低與成交量取當日,並以該資產市場的交易日查詢")
    void search_fillsQuoteFromMarketData() {
        when(repository.search("NV", 0, 20)).thenReturn(List.of(asset(2L, "NVDA", AssetType.STOCK, "US")));
        when(repository.count("NV")).thenReturn(1L);
        when(marketData.findDailyQuotes(Map.of(2L, TradingDay.of(AssetType.STOCK, "US")))).thenReturn(Map.of(2L,
            new DailyQuote(new BigDecimal("105"), OffsetDateTime.parse("2026-01-05T15:00:00Z"),
                new BigDecimal("100"), new BigDecimal("106"), new BigDecimal("99"), new BigDecimal("52100000"))));

        AssetDto dto = service.search("NV", 0, 20).items().get(0);

        assertThat(dto.latestPrice()).isEqualByComparingTo("105");
        assertThat(dto.change()).isEqualByComparingTo("5");
        assertThat(dto.changePercent()).isEqualByComparingTo("5.00");
        assertThat(dto.high()).isEqualByComparingTo("106");
        assertThat(dto.low()).isEqualByComparingTo("99");
        assertThat(dto.volumeText()).isEqualTo("52.1M");
    }

    @Test
    @DisplayName("沒有行情的資產:報價欄位全為 null,不塞預設值")
    void search_assetWithoutQuote_hasNullQuoteFields() {
        when(repository.search("", 0, 20)).thenReturn(List.of(asset(9L, "US10Y", AssetType.BOND, "US")));
        when(repository.count("")).thenReturn(1L);
        when(marketData.findDailyQuotes(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of());

        AssetDto dto = service.search("", 0, 20).items().get(0);

        assertThat(dto.latestPrice()).isNull();
        assertThat(dto.change()).isNull();
        assertThat(dto.changePercent()).isNull();
        assertThat(dto.high()).isNull();
        assertThat(dto.low()).isNull();
        assertThat(dto.volumeText()).isNull();
    }

    @Test
    @DisplayName("沒有前收(上市第一天):有最新價與高低點,但漲跌為 null")
    void search_withoutPreviousClose_hasNoChange() {
        when(repository.search("", 0, 20)).thenReturn(List.of(asset(3L, "BTC", AssetType.CRYPTO, "CRYPTO")));
        when(repository.count("")).thenReturn(1L);
        when(marketData.findDailyQuotes(Map.of(3L, TradingDay.UTC))).thenReturn(Map.of(3L,
            new DailyQuote(new BigDecimal("68000"), OffsetDateTime.parse("2026-01-05T15:00:00Z"),
                null, new BigDecimal("68100"), new BigDecimal("67000"), new BigDecimal("950"))));

        AssetDto dto = service.search("", 0, 20).items().get(0);

        assertThat(dto.latestPrice()).isEqualByComparingTo("68000");
        assertThat(dto.change()).isNull();
        assertThat(dto.changePercent()).isNull();
        assertThat(dto.volumeText()).isEqualTo("950");
    }

    @Test
    @DisplayName("成交量縮寫:K / M / B / T 取一位小數;零或 null 不顯示")
    void volumeText_compactNotation() {
        assertThat(AssetQueryService.volumeText(new BigDecimal("3000"))).isEqualTo("3.0K");
        assertThat(AssetQueryService.volumeText(new BigDecimal("1250000"))).isEqualTo("1.3M");
        assertThat(AssetQueryService.volumeText(new BigDecimal("28400000000"))).isEqualTo("28.4B");
        assertThat(AssetQueryService.volumeText(new BigDecimal("1500000000000"))).isEqualTo("1.5T");
        assertThat(AssetQueryService.volumeText(new BigDecimal("12.5"))).isEqualTo("12.5");
        assertThat(AssetQueryService.volumeText(BigDecimal.ZERO)).isNull();
        assertThat(AssetQueryService.volumeText(null)).isNull();
    }
}
