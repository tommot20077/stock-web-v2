package dowob.xyz.stockwebv2.infrastructure.fx;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 匯率換算值物件（不可變、純函式，無 I/O）。
 *
 * <p>{@link #rate(CurrencyCode, CurrencyCode)} 的解析順序固定為：
 * 同幣別 → 直接列 → 反向列 → 經 USD 三角換算 → 換不到回 empty（D-04）。
 *
 * <p>直接列必須優先於反向列：V2 seed 同時有 {@code USD/TWD 32.418} 與
 * {@code TWD/USD 0.030847}，兩者互為倒數只到 6 位有效數字，取直接列才與 seed 的意圖一致
 * （Pitfall 8）。換不到時回 empty，由呼叫端依 D-04 不計入該筆並回報。
 *
 * <p>反向除法使用 scale 16 / {@link RoundingMode#HALF_UP}；{@link #convert} 的最終結果為 scale 8。
 *
 * @author Yuan
 * @version 1.0.0
 */
public final class FxRates {

    private static final int INVERSE_SCALE = 16;
    private static final int RESULT_SCALE = 8;
    private static final FxRates EMPTY = new FxRates(Map.of());

    /** 直接列索引：{@code direct[base][quote] = 1 base 可換得的 quote 數量}。 */
    private final Map<CurrencyCode, Map<CurrencyCode, BigDecimal>> direct;

    private FxRates(Map<CurrencyCode, Map<CurrencyCode, BigDecimal>> direct) {
        this.direct = direct;
    }

    /**
     * 由匯率列建構；非正匯率列（{@code rate <= 0}）被忽略，避免反向除法除以零。
     *
     * @param rows 匯率列，不可為 null
     * @return 匯率值物件
     * @throws NullPointerException rows 為 null
     */
    public static FxRates of(Collection<FxRate> rows) {
        Objects.requireNonNull(rows, "rows must not be null");
        Map<CurrencyCode, Map<CurrencyCode, BigDecimal>> index = new EnumMap<>(CurrencyCode.class);
        for (FxRate row : rows) {
            if (row.rate().signum() <= 0) {
                continue;
            }
            index.computeIfAbsent(row.pairBase(), key -> new EnumMap<>(CurrencyCode.class))
                    .put(row.pairQuote(), row.rate());
        }
        return new FxRates(index);
    }

    /**
     * 不含任何匯率列的實例；除同幣別外一律換不到。
     *
     * @return 空的匯率值物件
     */
    public static FxRates empty() {
        return EMPTY;
    }

    /**
     * 取得 {@code 1 from = ? to} 的匯率。
     *
     * @param from 來源幣別，不可為 null
     * @param to   目標幣別，不可為 null
     * @return 匯率；無法換算時為 empty
     * @throws NullPointerException from 或 to 為 null
     */
    public Optional<BigDecimal> rate(CurrencyCode from, CurrencyCode to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from == to) {
            return Optional.of(BigDecimal.ONE);
        }
        Optional<BigDecimal> leg = singleLeg(from, to);
        if (leg.isPresent()) {
            return leg;
        }
        if (from == CurrencyCode.USD || to == CurrencyCode.USD) {
            return Optional.empty();
        }
        Optional<BigDecimal> toUsd = singleLeg(from, CurrencyCode.USD);
        Optional<BigDecimal> fromUsd = singleLeg(CurrencyCode.USD, to);
        if (toUsd.isPresent() && fromUsd.isPresent()) {
            return Optional.of(toUsd.get().multiply(fromUsd.get()));
        }
        return Optional.empty();
    }

    /**
     * 將金額由 from 幣別換算為 to 幣別。
     *
     * @param amount 金額，不可為 null
     * @param from   來源幣別
     * @param to     目標幣別
     * @return 換算結果（scale 8，HALF_UP）；無法換算時為 empty
     * @throws NullPointerException amount、from 或 to 為 null
     */
    public Optional<BigDecimal> convert(BigDecimal amount, CurrencyCode from, CurrencyCode to) {
        Objects.requireNonNull(amount, "amount must not be null");
        return rate(from, to).map(r -> amount.multiply(r).setScale(RESULT_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 單腿匯率：先找直接列，再找反向列的倒數。
     */
    private Optional<BigDecimal> singleLeg(CurrencyCode from, CurrencyCode to) {
        BigDecimal directRate = lookup(from, to);
        if (directRate != null) {
            return Optional.of(directRate);
        }
        BigDecimal inverseRate = lookup(to, from);
        if (inverseRate != null) {
            return Optional.of(BigDecimal.ONE.divide(inverseRate, INVERSE_SCALE, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    private BigDecimal lookup(CurrencyCode base, CurrencyCode quote) {
        Map<CurrencyCode, BigDecimal> quotes = direct.get(base);
        return quotes == null ? null : quotes.get(quote);
    }
}
