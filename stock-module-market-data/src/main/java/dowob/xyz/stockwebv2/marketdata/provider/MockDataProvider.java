package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock 市場資料提供者，用於開發與測試，以隨機 walk 模擬市場 tick。
 *
 * <p>啟動時透過 {@link AssetFacade#findAllTradeable()} 載入所有 active + tradeable
 * 資產，並按 {@link AssetType} 套用不同波動度（每日 σ）：
 * <ul>
 *   <li>STOCK：0.01（~1%/日）</li>
 *   <li>CRYPTO：0.03（~3%/日）</li>
 *   <li>FX：0.003（~0.3%/日）</li>
 *   <li>BOND：0.001（~0.1%/日）</li>
 * </ul>
 *
 * <p>{@link #fetchHistorical} 使用 {@code (assetId * 31 + from.epochSecond)} 作為
 * 隨機數 seed，確保同參數兩次呼叫結果完全一致（可重現）。
 *
 * <p>此 bean 僅在 {@code market-data.mock.enabled=true}（預設啟用）時載入，
 * 未來可透過設定停用以切換為真實 provider。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(
        prefix = "market-data.mock",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MockDataProvider implements DataProvider {

    private static final String PROVIDER_NAME = "mock";
    /** BigDecimal 精度上下文：12位有效數字。 */
    private static final MathContext MC = new MathContext(12);

    private final AssetFacade assetFacade;

    /** symbol → 資產元資料（初始化後只讀）。 */
    private final Map<String, AssetSummary> assets = new HashMap<>();

    /** symbol → fetchLatest 的即時狀態（mutable，thread-safe）。 */
    private final ConcurrentMap<String, MockAssetState> states = new ConcurrentHashMap<>();

    /**
     * 建構 MockDataProvider。
     *
     * @param assetFacade 資產 facade，用於啟動時載入可交易資產清單；不可為 null
     */
    public MockDataProvider(AssetFacade assetFacade) {
        this.assetFacade = assetFacade;
    }

    /**
     * Spring {@code @PostConstruct} 初始化：透過 {@link AssetFacade#findAllTradeable()}
     * 載入所有 active + tradeable 資產，並為每個 symbol 建立初始狀態。
     */
    @PostConstruct
    void initialize() {
        for (AssetSummary a : assetFacade.findAllTradeable()) {
            assets.put(a.symbol(), a);
            states.put(a.symbol(), new MockAssetState(basePrice(a), null));
        }
    }

    /**
     * 回傳此 provider 的識別名稱。
     *
     * @return {@code "mock"}
     */
    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    /**
     * 取得指定 symbol 的當前最新 tick。
     *
     * <p>基於前一個 tick 的價格與時間，以 geometric Brownian motion 做一步 random walk：
     * {@code nextPrice = lastPrice * exp(σ * sqrt(dt_days) * Z)}，其中 Z ~ N(0,1)。
     * 使用 {@link ThreadLocalRandom}，不保證可重現性。
     *
     * @param symbol 資產代號；不可為 null
     * @return 最新 {@link PriceTick}；不會為 null
     * @throws UnknownSymbolException 若 provider 未載入此 symbol
     * @throws NullPointerException   若 {@code symbol} 為 null
     */
    @Override
    public PriceTick fetchLatest(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        AssetSummary a = assets.get(symbol);
        if (a == null) {
            throw new UnknownSymbolException(symbol);
        }

        Instant now = Instant.now();
        MockAssetState state = states.get(symbol);
        BigDecimal newPrice;
        synchronized (state) {
            double dtDays;
            if (state.lastTickTime == null) {
                dtDays = 1.0 / 86400.0; // cold start：預設 1 秒
            } else {
                long deltaSeconds = Math.max(1L,
                        now.getEpochSecond() - state.lastTickTime.getEpochSecond());
                dtDays = deltaSeconds / 86400.0;
            }
            double z = ThreadLocalRandom.current().nextGaussian();
            newPrice = nextPrice(state.lastPrice, volatility(a.assetType()), dtDays, z);
            state.lastPrice = newPrice;
            state.lastTickTime = now;
        }

        BigDecimal volume = BigDecimal.valueOf(
                ThreadLocalRandom.current().nextLong(100, 10_000));
        return new PriceTick(symbol, now, newPrice, volume);
    }

    /**
     * 取得指定 symbol 在 {@code [from, to)} 半開區間內的歷史 tick，以 {@code interval} 為粒度。
     *
     * <p>使用 {@code (assetId * 31 + from.epochSecond)} 作為 seed，確保同參數兩次呼叫
     * 回傳完全相同的序列（可重現）。random walk 從各 symbol 的基準價出發，以
     * geometric Brownian motion 每步推進一個 {@code interval.duration()}。
     *
     * @param symbol   資產代號；不可為 null
     * @param from     範圍下界（含）；不可為 null
     * @param to       範圍上界（不含）；不可為 null，必須嚴格大於 {@code from}
     * @param interval K 線粒度；不可為 null
     * @return 按 time 升冪排列的 {@link PriceTick} list；不會為 null，範圍內無 bucket 時回空 list
     * @throws UnknownSymbolException   若 provider 未載入此 symbol
     * @throws IllegalArgumentException 若 {@code to <= from}
     * @throws NullPointerException     若任一參數為 null
     */
    @Override
    public List<PriceTick> fetchHistorical(String symbol, Instant from, Instant to,
                                            KlineInterval interval) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException(
                    "to must be after from: from=" + from + ", to=" + to);
        }

        AssetSummary a = assets.get(symbol);
        if (a == null) {
            throw new UnknownSymbolException(symbol);
        }

        // 以 (assetId, from) 決定 seed，確保可重現性
        long seed = a.id() * 31L + from.getEpochSecond();
        Random rnd = new Random(seed);

        BigDecimal price = basePrice(a);
        double sigma = volatility(a.assetType());
        double dtDays = interval.duration().toSeconds() / 86400.0;

        List<PriceTick> ticks = new ArrayList<>();
        Instant t = from;
        while (t.isBefore(to)) {
            double z = rnd.nextGaussian();
            price = nextPrice(price, sigma, dtDays, z);
            long vol = (long) (rnd.nextDouble() * 10_000) + 100;
            ticks.add(new PriceTick(symbol, t, price, BigDecimal.valueOf(vol)));
            t = t.plus(interval.duration());
        }
        return ticks;
    }

    // ── 測試可見的 package-private helpers ────────────────────────────────────

    /** 回傳已載入的資產數量，供測試驗證初始化行為。 */
    int loadedAssetCount() {
        return assets.size();
    }

    /** 回傳指定 AssetType 的每日波動度（σ），供測試驗證相對大小。 */
    double volatilityFor(AssetType type) {
        return volatility(type);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 計算 symbol 的基準價（確定性、per-symbol 不同）。
     * 公式：{@code baseFor(assetType) * (1 + abs(symbolHash % 100) / 100.0)}
     */
    private BigDecimal basePrice(AssetSummary a) {
        int symbolHash = Math.abs(a.symbol().hashCode() % 100);
        double base = switch (a.assetType()) {
            case STOCK -> 100.0 * (1.0 + symbolHash / 100.0);
            case CRYPTO -> 1000.0 * (1.0 + symbolHash / 100.0);
            case FX -> 1.0 * (1.0 + symbolHash / 1000.0);
            case BOND -> 100.0 * (1.0 + symbolHash / 100.0);
        };
        return new BigDecimal(base, MC).setScale(8, RoundingMode.HALF_UP);
    }

    /** 回傳指定 AssetType 的每日波動度（σ）。 */
    private double volatility(AssetType type) {
        return switch (type) {
            case STOCK -> 0.01;
            case CRYPTO -> 0.03;
            case FX -> 0.003;
            case BOND -> 0.001;
        };
    }

    /**
     * Geometric Brownian Motion 單步：
     * {@code nextPrice = last * exp(σ * sqrt(dt) * Z)}
     */
    private BigDecimal nextPrice(BigDecimal last, double sigma, double dtDays, double z) {
        double factor = Math.exp(sigma * Math.sqrt(dtDays) * z);
        return last.multiply(BigDecimal.valueOf(factor), MC)
                .setScale(8, RoundingMode.HALF_UP);
    }

    // ── inner state class ─────────────────────────────────────────────────────

    /**
     * fetchLatest 每個 symbol 的即時狀態，持有上一個 tick 的價格與時間。
     * 存取前須對 state 物件加鎖（{@code synchronized(state)}）。
     */
    static final class MockAssetState {

        /** 上一個 tick 的價格；初始為基準價。 */
        volatile BigDecimal lastPrice;

        /** 上一個 tick 的時間；cold start 時為 null。 */
        volatile Instant lastTickTime;

        MockAssetState(BigDecimal lastPrice, Instant lastTickTime) {
            this.lastPrice = lastPrice;
            this.lastTickTime = lastTickTime;
        }
    }
}
