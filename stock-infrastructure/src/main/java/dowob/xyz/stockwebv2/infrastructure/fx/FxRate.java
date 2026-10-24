package dowob.xyz.stockwebv2.infrastructure.fx;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 單一幣對的匯率列，語意為 {@code 1 pairBase = rate pairQuote}。
 *
 * <p>欄位刻意命名為 {@code pairBase} / {@code pairQuote}，而非 {@code baseCurrency}，
 * 以免與使用者基準幣（{@code users.base_currency}）混淆；{@code fx_rates.base_currency}
 * 指的是「幣對的基礎幣」。
 *
 * @param pairBase  幣對的基礎幣（被換算的一方）
 * @param pairQuote 幣對的報價幣（換算結果的幣別）
 * @param rate      匯率，1 單位 pairBase 可換得的 pairQuote 數量
 * @param rateTime  匯率時間
 * @author Yuan
 * @version 1.0.0
 */
public record FxRate(
        CurrencyCode pairBase,
        CurrencyCode pairQuote,
        BigDecimal rate,
        OffsetDateTime rateTime
) {

    /**
     * 檢查四個欄位皆不可為 null。
     *
     * @throws NullPointerException 任一欄位為 null
     */
    public FxRate {
        Objects.requireNonNull(pairBase, "pairBase must not be null");
        Objects.requireNonNull(pairQuote, "pairQuote must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(rateTime, "rateTime must not be null");
    }
}
