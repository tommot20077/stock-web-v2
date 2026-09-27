package dowob.xyz.stockwebv2.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TradingDay} 單元測試:各市場「一個交易日」從何時開始。
 *
 * <p>規則對齊 TradingView:股票依交易所時區切日、外匯依紐約 17:00 換日、加密貨幣依 UTC 00:00。
 *
 * @author Yuan
 * @version 1.0.0
 */
class TradingDayTest {

    @Test
    @DisplayName("美股:依紐約時區切日,UTC 午夜前後的兩筆 tick 分屬不同交易日")
    void usStock_splitsByNewYorkMidnight() {
        TradingDay day = TradingDay.of(AssetType.STOCK, "US");

        assertThat(day.zone()).isEqualTo(ZoneId.of("America/New_York"));
        // 2026-01-05T04:30Z = 紐約 1/4 23:30(冬令 UTC-5)→ 1/4 那天,起點 1/4 05:00Z
        assertThat(day.dayStart(Instant.parse("2026-01-05T04:30:00Z")))
            .isEqualTo(Instant.parse("2026-01-04T05:00:00Z"));
        // 2026-01-05T06:00Z = 紐約 1/5 01:00 → 1/5 那天
        assertThat(day.dayStart(Instant.parse("2026-01-05T06:00:00Z")))
            .isEqualTo(Instant.parse("2026-01-05T05:00:00Z"));
    }

    @Test
    @DisplayName("美股夏令時間:交易日起點隨 DST 變成 04:00Z")
    void usStock_followsDaylightSaving() {
        TradingDay day = TradingDay.of(AssetType.STOCK, "US");

        assertThat(day.dayStart(Instant.parse("2026-07-01T15:00:00Z")))
            .isEqualTo(Instant.parse("2026-07-01T04:00:00Z"));
    }

    @Test
    @DisplayName("台股、日股、德國:各自依當地時區")
    void otherMarkets_useLocalZone() {
        assertThat(TradingDay.of(AssetType.STOCK, "TW").zone()).isEqualTo(ZoneId.of("Asia/Taipei"));
        assertThat(TradingDay.of(AssetType.BOND, "JP").zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(TradingDay.of(AssetType.BOND, "DE").zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        // 台北 1/5 00:00 = 1/4 16:00Z
        assertThat(TradingDay.of(AssetType.STOCK, "TW").dayStart(Instant.parse("2026-01-05T01:00:00Z")))
            .isEqualTo(Instant.parse("2026-01-04T16:00:00Z"));
    }

    @Test
    @DisplayName("交易所代號(NASDAQ / NYSE)視同美股")
    void usExchangeCodes_areAliasesOfUs() {
        assertThat(TradingDay.of(AssetType.STOCK, "NASDAQ").zone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(TradingDay.of(AssetType.STOCK, "nyse").zone()).isEqualTo(ZoneId.of("America/New_York"));
    }

    @Test
    @DisplayName("外匯:紐約 17:00 換日,17:00 前屬前一交易日")
    void fx_rollsOverAtNewYorkFivePm() {
        TradingDay day = TradingDay.of(AssetType.FX, "FX");

        assertThat(day.zone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(day.startOffset()).isEqualTo(Duration.ofHours(17));
        // 紐約 1/5 16:59(= 21:59Z)→ 起點 1/4 17:00 紐約 = 1/4 22:00Z
        assertThat(day.dayStart(Instant.parse("2026-01-05T21:59:00Z")))
            .isEqualTo(Instant.parse("2026-01-04T22:00:00Z"));
        // 紐約 1/5 17:00(= 22:00Z)→ 新的交易日
        assertThat(day.dayStart(Instant.parse("2026-01-05T22:00:00Z")))
            .isEqualTo(Instant.parse("2026-01-05T22:00:00Z"));
    }

    @Test
    @DisplayName("加密貨幣:UTC 00:00 切日(對齊 Binance / TradingView 日 K)")
    void crypto_splitsAtUtcMidnight() {
        TradingDay day = TradingDay.of(AssetType.CRYPTO, "CRYPTO");

        assertThat(day.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(day.dayStart(Instant.parse("2026-01-05T23:59:59Z")))
            .isEqualTo(Instant.parse("2026-01-05T00:00:00Z"));
    }

    @Test
    @DisplayName("未知市場或 null:退回 UTC,不丟例外")
    void unknownMarket_fallsBackToUtc() {
        assertThat(TradingDay.of(AssetType.STOCK, "XX").zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(TradingDay.of(AssetType.STOCK, null).zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(TradingDay.of(null, null).zone()).isEqualTo(ZoneId.of("UTC"));
    }
}
