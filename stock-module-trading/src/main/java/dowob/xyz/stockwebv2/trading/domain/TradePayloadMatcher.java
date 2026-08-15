package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 判斷「帶同一個冪等鍵重送的請求」與「已寫入帳本的既有交易」是否為同一筆意圖（D-07）。
 *
 * <p>無狀態、無 I/O、無 Spring 註解的領域類別，定位與 {@link HoldingCalculator} 相同：
 * 比對規則是可獨立驗證的純函式，不該躲在 service 的交易流程裡。</p>
 *
 * <p>比對維度<strong>固定為六個</strong>：標的 id、交易類型、數量、價格、手續費、成交時間。
 * 交易的識別欄位（id / uuid / 冪等鍵）與入帳時間都是伺服器產生的，拿來比對必然不符；
 * 備註欄位則是刻意排除的 —— 使用者在逾時重試前順手改了備註，不該得到一個無法理解的 409。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class TradePayloadMatcher {

    private TradePayloadMatcher() {
    }

    /**
     * 比較兩個金額是否為同一個數值。
     *
     * <p><strong>絕不可改用 {@code equals}。</strong>{@link BigDecimal#equals} 連 scale 一起比，
     * {@code 10} 與 {@code 10.00000000} 會被判定為不同。而帳本的金額欄位是
     * {@code NUMERIC(24,8)}，從資料庫讀回來的既有值一律是 scale 8，客戶端重送的則是原始輸入值 ——
     * 用 {@code equals} 的話，每一次合法重試都會被判定成「同鍵不同 payload」而吃到假性 409，
     * 冪等保護反倒成了故障源。</p>
     *
     * @param a 其中一個金額，可為 null
     * @param b 另一個金額，可為 null
     * @return 兩邊皆 null 或數值相等時為 true；單邊 null 或數值不等時為 false
     */
    public static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    /**
     * 比較兩個時間戳是否指向同一個瞬間。
     *
     * <p><strong>絕不可改用 {@code equals}。</strong>{@link OffsetDateTime#equals} 連偏移量一起比，
     * {@code 2026-01-10T00:00:00Z} 與 {@code 2026-01-10T08:00:00+08:00} 是同一個瞬間卻會被判定為不同。
     * 偏移量在來回一趟中必然改變：Jackson 反序列化會依組態調整時區，PgJDBC 從
     * {@code TIMESTAMPTZ} 讀回的值也帶著連線時區而非客戶端原本送出的偏移量。
     * 冪等比對在意的是「同一個成交瞬間」，不是「同一種寫法」。</p>
     *
     * @param a 其中一個時間戳，可為 null
     * @param b 另一個時間戳，可為 null
     * @return 兩邊皆 null 或指向同一瞬間時為 true；單邊 null 或瞬間不同時為 false
     */
    public static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.toInstant().equals(b.toInstant());
    }

    /**
     * 比對既有交易與本次重送的請求內容是否為同一筆意圖。
     *
     * <p>標的以 {@code assetId} 而非代號比對：代號是否具備唯一約束未經查證，
     * 以已解析完成的資產 id 比對就不必依賴那個假設。</p>
     *
     * <p>比對的六個欄位之外一律不看：識別欄位與入帳時間由伺服器產生，備註欄位則依 D-06 排除。</p>
     *
     * @param existing   帳本中既有的交易（以同一個冪等鍵查出）
     * @param assetId    本次請求解析出的標的 id
     * @param type       本次請求的交易類型
     * @param quantity   本次請求的數量
     * @param price      本次請求的價格
     * @param fee        本次請求的手續費（已套用預設值）
     * @param executedAt 本次請求的成交時間（已套用預設值與精度正規化）
     * @return 六個受比對欄位全部相同時為 true；任一不同時為 false
     */
    public static boolean matches(
        TradeTransaction existing,
        Long assetId,
        TradeType type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        OffsetDateTime executedAt
    ) {
        return Objects.equals(existing.assetId(), assetId)
            && existing.type() == type
            && sameAmount(existing.quantity(), quantity)
            && sameAmount(existing.price(), price)
            && sameAmount(existing.fee(), fee)
            && sameInstant(existing.executedAt(), executedAt);
    }
}
