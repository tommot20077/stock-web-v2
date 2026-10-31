package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;

/**
 * 交易日內「沒有現金流」的股數變動（拆股、配股），今日損益的擴充點（Phase 04.2 D-10）。
 *
 * <p>04.1 沒有任何拆股來源，呼叫端一律傳入空 list。04.2 實作拆股時，把「當日因拆股 / 配股多出的股數」
 * 以 {@code quantityDelta} 傳進 {@link DayPnlCalculator#calculate}，計算器會自期初持股的反推中扣除，
 * 否則拆股當日的 {@code Q0×C} 會用拆前價乘上拆後股數，被算成暴漲暴跌。
 *
 * @param quantityDelta 當日非現金的股數變動量（拆股多出的股數為正）
 * @author Yuan
 * @version 1.0
 */
public record ShareAdjustment(BigDecimal quantityDelta) {
}
