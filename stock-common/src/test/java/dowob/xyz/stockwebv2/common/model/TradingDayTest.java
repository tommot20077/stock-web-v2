package dowob.xyz.stockwebv2.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("startOf:美股交易日起點為紐約當日午夜(10/30 為夏令 → 04:00Z)")
    void startOf_usStock_isNewYorkMidnight() {
        TradingDay day = TradingDay.of(AssetType.STOCK, "US");

        assertThat(day.startOf(LocalDate.parse("2026-10-30"))).isEqualTo(Instant.parse("2026-10-30T04:00:00Z"));
        // 2026-01-05 冬令 UTC-5
        assertThat(day.startOf(LocalDate.parse("2026-01-05"))).isEqualTo(Instant.parse("2026-01-05T05:00:00Z"));
    }

    @Test
    @DisplayName("startOf:台股交易日起點為台北當日午夜")
    void startOf_twStock_isTaipeiMidnight() {
        TradingDay day = TradingDay.of(AssetType.STOCK, "TW");

        assertThat(day.startOf(LocalDate.parse("2026-10-30"))).isEqualTo(Instant.parse("2026-10-29T16:00:00Z"));
    }

    @Test
    @DisplayName("startOf:外匯交易日 d 從紐約 d 17:00 起算(與 dayStart 對 tradingDate 的定義一致)")
    void startOf_fx_isNewYorkFivePmOfTradingDate() {
        TradingDay day = TradingDay.of(AssetType.FX, "FX");

        assertThat(day.startOf(LocalDate.parse("2026-10-30"))).isEqualTo(Instant.parse("2026-10-30T21:00:00Z"));
        // 1/4 交易日起點 = 紐約 1/4 17:00 冬令 = 22:00Z,與 fx_rollsOverAtNewYorkFivePm 的 dayStart 結果相同
        assertThat(day.startOf(LocalDate.parse("2026-01-04"))).isEqualTo(Instant.parse("2026-01-04T22:00:00Z"));
    }

    @Test
    @DisplayName("startOf:加密貨幣交易日起點為 UTC 00:00")
    void startOf_crypto_isUtcMidnight() {
        TradingDay day = TradingDay.of(AssetType.CRYPTO, "CRYPTO");

        assertThat(day.startOf(LocalDate.parse("2026-10-30"))).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
    }

    @Test
    @DisplayName("startOf 與 dayStart 同一公式:startOf(d) 本身與其後一小時都屬於交易日 d")
    void startOf_isConsistentWithDayStart() {
        List<TradingDay> days = List.of(
            TradingDay.of(AssetType.STOCK, "US"),
            TradingDay.of(AssetType.STOCK, "TW"),
            TradingDay.of(AssetType.FX, "FX"),
            TradingDay.of(AssetType.CRYPTO, "CRYPTO")
        );
        // 含夏令結束當天 2026-11-01 與冬令日期
        List<LocalDate> dates = List.of(
            LocalDate.parse("2026-10-30"),
            LocalDate.parse("2026-11-01"),
            LocalDate.parse("2026-01-05"),
            LocalDate.parse("2026-07-01")
        );
        for (TradingDay day : days) {
            for (LocalDate date : dates) {
                Instant start = day.startOf(date);
                assertThat(day.dayStart(start)).as("%s %s", day, date).isEqualTo(start);
                assertThat(day.dayStart(start.plusSeconds(3600))).as("%s %s +1h", day, date).isEqualTo(start);
            }
        }
    }

    @Test
    @DisplayName("startOf(null) 丟 NullPointerException")
    void startOf_rejectsNull() {
        TradingDay day = TradingDay.of(AssetType.STOCK, "US");

        assertThatThrownBy(() -> day.startOf(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("tradingDate must not be null");
    }
}
