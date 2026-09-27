package dowob.xyz.stockwebv2.common.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/**
 * 一個市場的「交易日」怎麼切:在哪個時區、當地幾點換日。
 *
 * <p>規則對齊 TradingView 與主流行情商:
 * <ul>
 *   <li>股票 / 債券依交易所當地時區的午夜切日(US → 紐約、TW → 台北、JP → 東京、DE → 柏林)</li>
 *   <li>外匯依慣例在紐約 17:00 換日</li>
 *   <li>加密貨幣依 UTC 00:00(Binance 全年固定 GMT+0 的日 K)</li>
 *   <li>無法辨識的市場退回 UTC,不丟例外——行情顯示不應因為一筆設定缺漏而失敗</li>
 * </ul>
 *
 * <p>日 K 的 bucket 起點、報價卡的「前收」與日損益都以此為準,三者因此永遠一致。
 * 換日點以<strong>當地時鐘</strong>計算,夏令時間切換日也維持「當地 17:00」而非固定小時數。
 *
 * @param zone        交易所時區
 * @param startOffset 當地午夜之後多久換日(股票為 0,外匯為 17 小時)
 * @author Yuan
 * @version 1.0.0
 */
public record TradingDay(ZoneId zone, Duration startOffset) {

    /** UTC 午夜換日;加密貨幣與未知市場使用。 */
    public static final TradingDay UTC = new TradingDay(ZoneId.of("UTC"), Duration.ZERO);

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /**
     * @param zone        交易所時區,不可為 null
     * @param startOffset 當地午夜之後多久換日,須介於 [0, 24h)
     */
    public TradingDay {
        Objects.requireNonNull(zone, "zone must not be null");
        Objects.requireNonNull(startOffset, "startOffset must not be null");
        if (startOffset.isNegative() || startOffset.compareTo(Duration.ofDays(1)) >= 0) {
            throw new IllegalArgumentException("startOffset must be within [0, 24h): " + startOffset);
        }
    }

    /**
     * 依資產類型與市場代號取得交易日規則。
     *
     * @param assetType 資產類型,可為 null
     * @param market    市場或交易所代號(assets.market),大小寫不拘,可為 null
     * @return 交易日規則;無法辨識時為 {@link #UTC}
     */
    public static TradingDay of(AssetType assetType, String market) {
        if (assetType == AssetType.CRYPTO) {
            return UTC;
        }
        if (assetType == AssetType.FX) {
            return new TradingDay(NEW_YORK, Duration.ofHours(17));
        }
        if (market == null) {
            return UTC;
        }
        return switch (market.trim().toUpperCase(Locale.ROOT)) {
            case "US", "NASDAQ", "NYSE", "AMEX" -> new TradingDay(NEW_YORK, Duration.ZERO);
            case "TW", "TWSE", "TPEX" -> new TradingDay(ZoneId.of("Asia/Taipei"), Duration.ZERO);
            case "JP", "TSE" -> new TradingDay(ZoneId.of("Asia/Tokyo"), Duration.ZERO);
            case "DE", "XETRA" -> new TradingDay(ZoneId.of("Europe/Berlin"), Duration.ZERO);
            default -> UTC;
        };
    }

    /**
     * 給定時刻所屬交易日的起點。
     *
     * @param time 時刻,不可為 null
     * @return 該交易日開始的瞬間
     */
    public Instant dayStart(Instant time) {
        Objects.requireNonNull(time, "time must not be null");
        LocalDate tradingDate = time.atZone(zone).toLocalDateTime().minus(startOffset).toLocalDate();
        return tradingDate.atStartOfDay().plus(startOffset).atZone(zone).toInstant();
    }
}
