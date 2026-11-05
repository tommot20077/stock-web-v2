package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 判斷「帶同一個冪等鍵重送的現金股利請求」與「已寫入帳本的既有列」是否為同一筆意圖（DIV-01）。
 *
 * <p>無狀態、無 I/O、無 Spring 註解的領域類別，定位與 {@link TradePayloadMatcher}、{@link CashEntryPayloadMatcher}
 * 相同；金額與時間的比較直接沿用 {@link TradePayloadMatcher#sameAmount} / {@link TradePayloadMatcher#sameInstant}，
 * 不重寫規則。</p>
 *
 * <p>比對維度：標的（assetId）、{@code amount}、{@code withholdingTax}、沖銷對象，以及<strong>有送才比</strong>的
 * {@code paidAt}。設計理由：</p>
 * <ul>
 *   <li>金額用 {@code compareTo} 而非 {@code equals}——客戶端送 {@code 120}，資料庫 {@code NUMERIC(24,8)} 讀回
 *       {@code 120.00000000}；{@code equals} 會把 scale 不同視為不同，讓每一次合法重送都吃到假性 409；</li>
 *   <li>{@code paidAt} 只在請求有送時比較——省略代表交由伺服器決定，每次重試的「現在」都不同，若照比就是假性 409；</li>
 *   <li><strong>不比</strong> {@code note}——使用者在逾時重試前順手改了備註，不該得到一個無法理解的 409
 *       （與交易 D-06、cash-entries 同理）；</li>
 *   <li>識別欄位（id / uuid / 冪等鍵）與寫入時間是伺服器產生的，拿來比對必然不符，因此不比。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
public final class DividendPayloadMatcher {

    private DividendPayloadMatcher() {
    }

    /**
     * 比對既有列與本次重送的請求內容是否為同一筆意圖。
     *
     * @param existing           以同一把冪等鍵查出的既有列
     * @param assetId            本次請求解析出的標的主鍵
     * @param amount             本次請求的實領金額
     * @param withholdingTax     本次請求的預扣稅，<strong>由呼叫端先把省略正規化為 0</strong>（契約：省略視為 0）
     * @param paidAtOrNull       本次請求<strong>明確送出</strong>的入帳時間（已截到微秒）；未送為 null，此時略過時間維度
     * @param reversalOfIdOrNull 本次請求要沖銷的原列內部主鍵（由 service 以使用者範圍解析）；一般紀錄為 null。
     *                           以 {@link Objects#equals} 比對：同 key 在一般紀錄與沖銷間切換、或換沖銷對象都是不同意圖
     * @return 受比對的欄位全部相同時為 true；任一不同時為 false
     */
    public static boolean matches(
        CashDividend existing,
        Long assetId,
        BigDecimal amount,
        BigDecimal withholdingTax,
        OffsetDateTime paidAtOrNull,
        Long reversalOfIdOrNull
    ) {
        return Objects.equals(existing.assetId(), assetId)
            && Objects.equals(existing.reversalOfId(), reversalOfIdOrNull)
            && TradePayloadMatcher.sameAmount(existing.amount(), amount)
            && TradePayloadMatcher.sameAmount(existing.withholdingTax(), withholdingTax)
            && (paidAtOrNull == null || TradePayloadMatcher.sameInstant(existing.paidAt(), paidAtOrNull));
    }
}
