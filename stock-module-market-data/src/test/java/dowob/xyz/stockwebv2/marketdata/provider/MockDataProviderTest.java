package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MockDataProvider} 超出契約範圍的單元測試。
 *
 * <p>涵蓋契約測試未覆蓋的行為：
 * <ul>
 *   <li>初始化載入資產數量</li>
 *   <li>{@code fetchHistorical} 可重現性（同參數兩次呼叫結果相同）</li>
 *   <li>不同 symbol 產生不同序列</li>
 *   <li>各 AssetType 的波動度相對大小</li>
 *   <li>{@code fetchLatest} 持續推進狀態</li>
 *   <li>時間範圍小於一個 bucket 時回傳空 list</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class MockDataProviderTest {

    private MockDataProvider provider() {
        var fake = new MockDataProviderContractTest.FakeAssetFacade(
                List.of(
                        new AssetSummary(1L, "AAPL", "Apple", AssetType.STOCK, "NASDAQ", true, true),
                        new AssetSummary(2L, "BTC", "Bitcoin", AssetType.CRYPTO, "BINANCE", true, true),
                        new AssetSummary(3L, "USD/TWD", "FX", AssetType.FX, "FX", true, true),
                        new AssetSummary(4L, "US10Y", "Bond", AssetType.BOND, "BOND", true, true)
                )
        );
        var p = new MockDataProvider(fake);
        p.initialize();
        return p;
    }

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("initialize()：載入全部 4 個資產")
    void initialize_loadsAllAssets() {
        assertThat(provider().loadedAssetCount()).isEqualTo(4);
    }

    // ── fetchHistorical 可重現性 ──────────────────────────────────────────────

    @Test
    @DisplayName("fetchHistorical()：同參數兩次呼叫 → 結果完全相同（可重現）")
    void fetchHistorical_isReproducibleAcrossCalls() {
        var p = provider();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plus(Duration.ofMinutes(5));

        var a = p.fetchHistorical("AAPL", from, to, KlineInterval.ONE_MINUTE);
        var b = p.fetchHistorical("AAPL", from, to, KlineInterval.ONE_MINUTE);

        assertThat(a).hasSize(5);
        assertThat(a).isEqualTo(b);
    }

    // ── fetchHistorical 不同 symbol → 不同序列 ───────────────────────────────

    @Test
    @DisplayName("fetchHistorical()：不同 symbol 使用不同 seed → 產生不同價格序列")
    void fetchHistorical_differentSymbolsProduceDifferentSequences() {
        var p = provider();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plus(Duration.ofMinutes(5));

        var aapl = p.fetchHistorical("AAPL", from, to, KlineInterval.ONE_MINUTE);
        var btc = p.fetchHistorical("BTC", from, to, KlineInterval.ONE_MINUTE);

        // 筆數相同，但第一筆價格因 seed 不同而不同
        assertThat(aapl).hasSameSizeAs(btc);
        assertThat(aapl.get(0).price()).isNotEqualByComparingTo(btc.get(0).price());
    }

    // ── volatility 相對大小 ───────────────────────────────────────────────────

    @Test
    @DisplayName("volatilityFor()：CRYPTO > STOCK > FX > BOND")
    void volatilityFor_assetTypes_areDifferent() {
        var p = provider();
        double stock = p.volatilityFor(AssetType.STOCK);
        double crypto = p.volatilityFor(AssetType.CRYPTO);
        double fx = p.volatilityFor(AssetType.FX);
        double bond = p.volatilityFor(AssetType.BOND);

        assertThat(crypto).isGreaterThan(stock);
        assertThat(stock).isGreaterThan(fx);
        assertThat(fx).isGreaterThan(bond);
    }

    // ── fetchLatest 狀態推進 ──────────────────────────────────────────────────

    @Test
    @DisplayName("fetchLatest()：連續兩次呼叫 → 第二個 tick 的 time >= 第一個，price 非 null")
    void fetchLatest_advancesPriceState() {
        var p = provider();
        var t1 = p.fetchLatest("AAPL");
        var t2 = p.fetchLatest("AAPL");

        assertThat(t2.time()).isAfterOrEqualTo(t1.time());
        assertThat(t2.price()).isNotNull();
    }

    // ── 範圍恰好一個 bucket ────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchHistorical()：範圍 30 秒（< 1 分鐘 interval）→ 只有 from 時間點在 [from,to)，回傳 1 筆")
    void fetchHistorical_rangeSmallerThanInterval_returnsOneTick() {
        var p = provider();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        // to = from + 30s；from 在 [from, to) 內，所以 tick at `from` 合法
        Instant to = from.plusSeconds(30);

        var ticks = p.fetchHistorical("AAPL", from, to, KlineInterval.ONE_MINUTE);

        // tick 時間戳為 `from`，而 `from < to`，故回傳 1 筆
        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).time()).isEqualTo(from);
    }

    @Test
    @DisplayName("fetchHistorical()：to 恰好等於下一個 step（exactly 1 interval），僅回傳 from 那一筆")
    void fetchHistorical_exactlyOneInterval_returnsOneTick() {
        var p = provider();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plus(KlineInterval.ONE_MINUTE.duration()); // from + 1m

        var ticks = p.fetchHistorical("AAPL", from, to, KlineInterval.ONE_MINUTE);

        // [from, to) 只包含 `from` — 下一個 step 是 `to`，不在半開區間內
        assertThat(ticks).hasSize(1);
        assertThat(ticks.get(0).time()).isEqualTo(from);
    }
}
