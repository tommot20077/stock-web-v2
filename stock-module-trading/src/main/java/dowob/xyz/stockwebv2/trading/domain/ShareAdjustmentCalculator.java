package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 拆股 / 合股的股數換算（純函式，無 Spring、無 I/O；D-08）。
 *
 * <p>以<strong>事件日持股</strong>乘上比例得到新股數，回傳股數變動量；呼叫端再把變動量交給
 * {@link HoldingCalculator#applyShareAdjustment} 攤薄或墊高均價（D-07：配股與拆股共用同一套計算）。
 * 不可直接用目前持倉乘比例，否則補記舊事件時會把事件後買入的股數也乘進去。
 *
 * @author Yuan
 * @version 1.0
 */
public final class ShareAdjustmentCalculator {

    private static final int QUANTITY_SCALE = 8;

    private ShareAdjustmentCalculator() {
    }

    /**
     * 計算拆股 / 合股造成的股數變動。
     *
     * <p>新股數 = 事件日持股 × ratioNew ÷ ratioOld（scale 8、無條件捨去），回傳「新股數 − 事件日持股」。
     * 例：4:1 對 100 股 → +300；1:10 對 105 股 → −94.5；1:3 對 100 股 → 新股數 33.33333333、變動 −66.66666667。
     *
     * @param quantityAsOf 事件日持股，須大於 0（service 層已先以 409 擋下無持倉的情況，這裡是程式錯誤防線）
     * @param ratioNew     比例中的新股數（例 4:1 的 4），須大於 0
     * @param ratioOld     比例中的舊股數（例 4:1 的 1），須大於 0 且不等於 ratioNew
     * @return 股數變動量（拆股為正、合股為負），scale 不超過 8
     * @throws IllegalArgumentException 事件日持股為 null 或非正、比例非正或兩者相等
     */
    public static BigDecimal splitDelta(BigDecimal quantityAsOf, int ratioNew, int ratioOld) {
        if (quantityAsOf == null || quantityAsOf.signum() <= 0) {
            throw new IllegalArgumentException("quantityAsOf must be greater than 0");
        }
        if (ratioNew <= 0 || ratioOld <= 0) {
            throw new IllegalArgumentException("ratio must be greater than 0");
        }
        if (ratioNew == ratioOld) {
            throw new IllegalArgumentException("ratioNew must differ from ratioOld");
        }
        // DOWN：合股的零股以小數保留，但捨入不得讓使用者憑空多出股數
        BigDecimal next = quantityAsOf
            .multiply(BigDecimal.valueOf(ratioNew))
            .divide(BigDecimal.valueOf(ratioOld), QUANTITY_SCALE, RoundingMode.DOWN);
        return next.subtract(quantityAsOf);
    }
}
