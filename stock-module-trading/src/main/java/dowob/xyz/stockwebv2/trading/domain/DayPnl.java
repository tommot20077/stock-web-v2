package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 單一標的的今日價格損益與其比率分母（原幣）。
 *
 * @param pnl   今日價格損益
 * @param basis 比率分母（D-16）：前收時市值加上今日買入成本，即 {@code Q0×C + ΣB×pb}
 * @author Yuan
 * @version 1.0
 */
public record DayPnl(BigDecimal pnl, BigDecimal basis) {

    private static final int RATIO_SCALE = 8;

    /**
     * 今日損益比率 = {@code pnl / basis}（scale 8、HALF_UP）。
     *
     * @return 比率；分母為零時回 null（沒有基準可比，不是 0%）
     */
    public BigDecimal ratio() {
        if (basis.signum() == 0) {
            return null;
        }
        return pnl.divide(basis, RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
