package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 判斷「帶同一個冪等鍵重送的股數調整請求」與「已寫入帳本的既有列」是否為同一筆意圖（ADJ-01）。
 *
 * <p>無狀態、無 I/O、無 Spring 註解的領域類別，定位與 {@link TradePayloadMatcher}、{@link CashEntryPayloadMatcher}
 * 相同；數量比較沿用 {@link TradePayloadMatcher#sameAmount}（compareTo，scale 不同、數值相等視為相同）。</p>
 *
 * <p>比對維度：標的（assetId）、type、配股的 quantity <em>或</em> 拆股的 ratioNew + ratioOld、除權日、沖銷對象。
 * 刻意<strong>不比</strong>的欄位：</p>
 * <ul>
 *   <li>{@code note}——使用者在逾時重試前順手改了備註，不該得到一個無法理解的 409（與交易 D-06 同理）；</li>
 *   <li>拆股的 {@code quantityDelta}——它是後端依除權日持股算出的值、不在請求中，請求沒有東西可以跟它比；
 *       配股沒有獨立的 quantity 欄位，實領股數就存在 {@code quantity_delta}，因此配股比的是請求 quantity 與它；</li>
 *   <li>另一種 type 的欄位（配股順帶送的 ratio、拆股順帶送的 quantity）——寫入時忽略，比對時也忽略。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ShareAdjustmentPayloadMatcher {

    private ShareAdjustmentPayloadMatcher() {
    }

    /**
     * 比對既有列與本次重送的請求內容是否為同一筆意圖。
     *
     * @param existing              以同一把冪等鍵查出的既有列
     * @param assetId               本次請求解析出的標的主鍵
     * @param type                  本次請求的調整類型
     * @param quantityOrNull        本次請求的配股實領股數；拆股時不參與比對
     * @param ratioNewOrNull        本次請求的拆股比例分子；配股時不參與比對
     * @param ratioOldOrNull        本次請求的拆股比例分母；配股時不參與比對
     * @param effectiveDate         本次請求的除權日
     * @param reversalOfIdOrNull    本次請求要沖銷的原列內部主鍵（由 service 以使用者範圍解析）；一般紀錄為 null。
     *                              以 {@link Objects#equals} 比對：同 key 在一般紀錄與沖銷間切換、或換沖銷對象都是不同意圖
     * @return 受比對的欄位全部相同時為 true；任一不同時為 false
     */
    public static boolean matches(
        ShareAdjustmentEntry existing,
        Long assetId,
        ShareAdjustmentType type,
        BigDecimal quantityOrNull,
        Integer ratioNewOrNull,
        Integer ratioOldOrNull,
        LocalDate effectiveDate,
        Long reversalOfIdOrNull
    ) {
        if (existing.type() != type
            || !Objects.equals(existing.assetId(), assetId)
            || !Objects.equals(existing.effectiveDate(), effectiveDate)
            || !Objects.equals(existing.reversalOfId(), reversalOfIdOrNull)) {
            return false;
        }
        return switch (type) {
            case STOCK_DIVIDEND -> quantityOrNull != null
                && TradePayloadMatcher.sameAmount(existing.quantityDelta(), quantityOrNull);
            case SPLIT -> Objects.equals(existing.ratioNew(), ratioNewOrNull)
                && Objects.equals(existing.ratioOld(), ratioOldOrNull);
        };
    }
}
