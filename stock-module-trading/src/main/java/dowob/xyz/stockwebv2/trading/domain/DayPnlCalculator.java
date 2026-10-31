package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 今日價格損益的恆等式（純函式，無 Spring、無 I/O）。
 *
 * <h2>恆等式</h2>
 * 設 Q0 為交易日起點的持股、B/pb 為今日買入的數量與價、S/ps 為今日賣出的數量與價、
 * Q1 為目前持股（{@code Q1 = Q0 + ΣB − ΣS}）、L 為最新價、C 為前收。D-12 的三段分解
 * <pre>(Q0 − ΣS)×(L − C) + ΣB×(L − pb) + ΣS×(ps − C)</pre>
 * 展開後恰好等於
 * <pre>Q1×L − Q0×C − Σ(B×pb) + Σ(S×ps)</pre>
 * （{@code Q0 − ΣS} 為負時同樣成立）。本類別用右式計算，所以<strong>不必判斷賣掉的是昨天的股還是今天買的股</strong>：
 * 當沖、買了又全賣、部分賣出都自然正確。單元測試以性質測試把兩種算法逐值對照。
 *
 * <h2>為什麼不需要日級快照表</h2>
 * Q0 可由目前持股與今日成交反推：{@code Q0 = Q1 − ΣB + ΣS}，所以只需要 append-only 的交易帳本與
 * 行情的前收，不必另存每日持股快照。
 *
 * <h2>前收何時必要（D-26 收窄 D-17）</h2>
 * 右式中 C 的係數只有 {@code −Q0}。Q0 為零（今天才首次買入，或當日買賣來回後歸零）時 C 不參與計算，
 * 所以沒有前收也能算；只有 Q0 大於零而缺前收，才無法計算並回 {@link Optional#empty()}。
 * 最新價 L 一律必要。缺價格時回 empty 而不是塞 0，「缺資料」與「真實的零」才分得出來。
 *
 * <h2>只含價格損益</h2>
 * 不含匯差，也<strong>不扣手續費</strong>（D-15）——這與 {@code HoldingCalculator} 的已實現損益會扣手續費
 * 是不同語意，兩者不可相加或互相對帳。
 *
 * <h2>04.2 擴充點：{@link ShareAdjustment}</h2>
 * 拆股 / 配股當日的股數變動沒有現金流，必須從 Q0 的反推中扣除：
 * {@code Q0 = Q1 − ΣB + ΣS − Σ(adjustments)}，C 不調整，此時 {@code Q0(拆前股數)×C(拆前前收)}
 * 即為前收時市值，與 04.2 D-10 的「前收依比例調整」數值相同。04.1 一律傳入空 list。
 *
 * @author Yuan
 * @version 1.0
 */
public final class DayPnlCalculator {

    private static final int MONEY_SCALE = 8;

    private DayPnlCalculator() {
    }

    /**
     * 計算單一標的的今日價格損益（原幣）。
     *
     * @param quantityNow   目前持股 Q1（今天全數賣出時為 0）
     * @param latestPrice   最新價 L；null 表示完全沒有行情
     * @param previousClose 前收 C；null 表示本交易日前沒有任何成交
     * @param fills         該標的在本交易日起點之後的成交（呼叫端已依各自交易日起點過濾）
     * @param adjustments   當日非現金股數變動（拆股 / 配股）；04.1 恆為空
     * @return 今日損益與比率分母；缺必要價格或帳本時序矛盾（期初持股為負）時為 empty
     */
    public static Optional<DayPnl> calculate(
        BigDecimal quantityNow,
        BigDecimal latestPrice,
        BigDecimal previousClose,
        List<DayFill> fills,
        List<ShareAdjustment> adjustments
    ) {
        if (latestPrice == null) {
            return Optional.empty();
        }
        BigDecimal bought = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal boughtCost = BigDecimal.ZERO;
        BigDecimal soldProceeds = BigDecimal.ZERO;
        for (DayFill fill : fills) {
            BigDecimal amount = fill.quantity().multiply(fill.price());
            // switch 表達式對列舉必須窮盡：未來新增 TradeType 時，這裡會編譯失敗而不是靜默漏算。
            boolean isBuy = switch (fill.type()) {
                case BUY -> true;
                case SELL -> false;
            };
            if (isBuy) {
                bought = bought.add(fill.quantity());
                boughtCost = boughtCost.add(amount);
            } else {
                sold = sold.add(fill.quantity());
                soldProceeds = soldProceeds.add(amount);
            }
        }
        BigDecimal adjusted = BigDecimal.ZERO;
        for (ShareAdjustment adjustment : adjustments) {
            adjusted = adjusted.add(adjustment.quantityDelta());
        }
        BigDecimal openingQuantity = quantityNow.subtract(bought).add(sold).subtract(adjusted);
        if (openingQuantity.signum() < 0) {
            // 補登造成的時序矛盾：期初持股不可能為負，不硬算一個看似合理的數字。
            return Optional.empty();
        }
        if (openingQuantity.signum() > 0 && previousClose == null) {
            return Optional.empty();
        }
        // Q0 = 0 時 C 的係數為 0，沒有前收也能算。
        BigDecimal close = previousClose == null ? BigDecimal.ZERO : previousClose;
        BigDecimal openingValue = openingQuantity.multiply(close);
        BigDecimal pnl = quantityNow.multiply(latestPrice)
            .subtract(openingValue)
            .subtract(boughtCost)
            .add(soldProceeds);
        BigDecimal basis = openingValue.add(boughtCost);
        return Optional.of(new DayPnl(
            pnl.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            basis.setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
    }
}
