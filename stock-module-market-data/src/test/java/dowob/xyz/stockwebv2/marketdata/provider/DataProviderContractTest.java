package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.common.model.KlineInterval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DataProvider} 契約測試基底類別。
 *
 * <p>具體 provider 的測試類別繼承此基底，並提供 {@link #provider()} 與 {@link #knownSymbol()} 實作，
 * 即可自動執行所有契約測試，確保各 provider 行為一致。
 *
 * <p>此類別為抽象類別，Surefire 不會直接執行；由子類別（例如 {@code MockDataProviderTest}）繼承後執行。
 *
 * <h2>驗證內容</h2>
 * <ul>
 *   <li>{@link DataProvider#name()} 不為空</li>
 *   <li>{@link DataProvider#fetchLatest(String)} 對已知 symbol 回傳正確 tick</li>
 *   <li>{@link DataProvider#fetchLatest(String)} 對未知 symbol 拋出 {@link UnknownSymbolException}</li>
 *   <li>{@link DataProvider#fetchHistorical(String, Instant, Instant, KlineInterval)} 回傳範圍內 ticks，按時間升冪排序</li>
 *   <li>{@link DataProvider#fetchHistorical} 對無效時間範圍拋出 {@link IllegalArgumentException}</li>
 *   <li>{@link DataProvider#fetchHistorical} 對未知 symbol 拋出 {@link UnknownSymbolException}</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
public abstract class DataProviderContractTest {

    /**
     * 子類別提供受測 {@link DataProvider} 實例。
     *
     * @return 受測 provider；不可為 null
     */
    protected abstract DataProvider provider();

    /**
     * 子類別提供 provider 必定能查到的 symbol（例如 mock 提供 {@code "AAPL"}）。
     *
     * @return 已知可查詢的 symbol；不可為 null 或空字串
     */
    protected abstract String knownSymbol();

    // ── name ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("name()：回傳不為 null 且不為空字串")
    void name_isNotNullNorBlank() {
        assertThat(provider().name()).isNotBlank();
    }

    // ── fetchLatest ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchLatest()：已知 symbol → 回傳非 null tick，symbol/price/time 皆有值")
    void fetchLatest_returnsTickForKnownSymbol() {
        PriceTick tick = provider().fetchLatest(knownSymbol());

        assertThat(tick).isNotNull();
        assertThat(tick.symbol()).isEqualTo(knownSymbol());
        assertThat(tick.price()).isNotNull();
        assertThat(tick.time()).isNotNull();
    }

    @Test
    @DisplayName("fetchLatest()：未知 symbol → 拋出 UnknownSymbolException")
    void fetchLatest_unknownSymbol_throwsUnknownSymbolException() {
        assertThatThrownBy(() -> provider().fetchLatest("UNKNOWN_SYMBOL_XYZ_12345"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    // ── fetchHistorical ──────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchHistorical()：合法範圍 → 回傳非 null list，若非空則按 time 升冪排列且在範圍內")
    void fetchHistorical_returnsTicksInRange_ascendingByTime() {
        Instant now  = Instant.now();
        Instant from = now.minus(2, ChronoUnit.HOURS);
        Instant to   = now.minus(1, ChronoUnit.HOURS);

        List<PriceTick> ticks = provider().fetchHistorical(
                knownSymbol(), from, to, KlineInterval.ONE_MINUTE);

        assertThat(ticks).isNotNull();

        // 若非空：須按 time 升冪排列，且每筆須在 [from, to) 範圍內
        for (int i = 0; i < ticks.size() - 1; i++) {
            assertThat(ticks.get(i).time())
                    .isBeforeOrEqualTo(ticks.get(i + 1).time());
        }
        for (PriceTick t : ticks) {
            assertThat(t.time()).isAfterOrEqualTo(from).isBefore(to);
            assertThat(t.symbol()).isEqualTo(knownSymbol());
        }
    }

    @Test
    @DisplayName("fetchHistorical()：to == from 或 to < from → 拋出 IllegalArgumentException")
    void fetchHistorical_invalidRange_throwsIllegalArgumentException() {
        Instant t = Instant.now();

        // to == from
        assertThatThrownBy(() -> provider().fetchHistorical(
                knownSymbol(), t, t, KlineInterval.ONE_MINUTE))
                .isInstanceOf(IllegalArgumentException.class);

        // to < from
        assertThatThrownBy(() -> provider().fetchHistorical(
                knownSymbol(), t.plusSeconds(1), t, KlineInterval.ONE_MINUTE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fetchHistorical()：未知 symbol → 拋出 UnknownSymbolException")
    void fetchHistorical_unknownSymbol_throwsUnknownSymbolException() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> provider().fetchHistorical(
                "UNKNOWN_SYMBOL_XYZ_12345",
                now.minus(1, ChronoUnit.HOURS),
                now,
                KlineInterval.ONE_MINUTE))
                .isInstanceOf(UnknownSymbolException.class);
    }
}
