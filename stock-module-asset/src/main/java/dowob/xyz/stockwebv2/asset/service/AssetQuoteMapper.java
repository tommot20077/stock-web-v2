package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 資產 → {@link AssetDto} 的報價映射，{@code GET /api/v1/assets} 與觀察清單共用（D-19「同一套」）。
 *
 * <p>漲跌 = 最新價 − 前收、漲跌 % 取兩位（HALF_UP）、成交量縮寫；沒有行情（quote 為 null）時六個報價欄位
 * 皆為 null（前端顯示「—」），不塞預設價。兩個端點必須逐欄同形，因此只能有這一份實作。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class AssetQuoteMapper {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final String[] VOLUME_SUFFIXES = {"K", "M", "B", "T"};

    private AssetQuoteMapper() {
    }

    /**
     * @param asset 資產
     * @param quote 該資產市場交易日的行情統計；沒有行情時為 null
     * @return 帶報價欄位的資產 DTO
     */
    static AssetDto toDto(Asset asset, DailyQuote quote) {
        BigDecimal change = null;
        BigDecimal changePercent = null;
        if (quote != null && quote.previousClose() != null && quote.previousClose().signum() != 0) {
            change = quote.latestPrice().subtract(quote.previousClose());
            changePercent = change.multiply(HUNDRED).divide(quote.previousClose(), 2, RoundingMode.HALF_UP);
        }
        return new AssetDto(
            asset.uuid(),
            asset.symbol(),
            asset.name(),
            asset.assetType(),
            asset.market(),
            asset.currency(),
            asset.sector(),
            asset.tradeable(),
            quote == null ? null : quote.latestPrice(),
            change,
            changePercent,
            quote == null ? null : volumeText(quote.volume()),
            quote == null ? null : quote.high(),
            quote == null ? null : quote.low()
        );
    }

    /**
     * 成交量縮寫:千以上取一位小數並加 K / M / B / T;零或 null 回 null(前端顯示「—」)。
     *
     * @param volume 成交量
     * @return 縮寫字串
     */
    static String volumeText(BigDecimal volume) {
        if (volume == null || volume.signum() == 0) {
            return null;
        }
        BigDecimal scaled = volume.abs();
        int unit = -1;
        while (scaled.compareTo(THOUSAND) >= 0 && unit < VOLUME_SUFFIXES.length - 1) {
            scaled = scaled.divide(THOUSAND);
            unit++;
        }
        if (unit < 0) {
            return volume.stripTrailingZeros().toPlainString();
        }
        return scaled.setScale(1, RoundingMode.HALF_UP).toPlainString() + VOLUME_SUFFIXES[unit];
    }
}
