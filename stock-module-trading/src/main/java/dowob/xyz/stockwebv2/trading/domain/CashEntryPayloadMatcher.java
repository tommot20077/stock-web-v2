package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 判斷「帶同一個冪等鍵重送的入出金請求」與「已寫入帳本的既有分錄」是否為同一筆意圖（D-11）。
 *
 * <p>無狀態、無 I/O、無 Spring 註解的領域類別，定位與 {@link TradePayloadMatcher} 相同；
 * 金額與時間的比較直接沿用其 {@code sameAmount} / {@code sameInstant}，不重寫規則。
 * 04.1-06 的沖銷路徑沿用本類別。</p>
 *
 * <p>比對維度為：方向、幣別、金額、沖銷對象，以及<strong>有送才比</strong>的發生時間。分錄的識別欄位
 * （id / uuid / 冪等鍵）與入帳時間都是伺服器產生的，拿來比對必然不符；備註欄位則是刻意排除的
 * —— 使用者在逾時重試前順手改了備註，不該得到一個無法理解的 409（與交易的 D-06 同理）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class CashEntryPayloadMatcher {

    private CashEntryPayloadMatcher() {
    }

    /**
     * 比對既有分錄與本次重送的請求內容是否為同一筆意圖。
     *
     * <p>{@code requestedOccurredAt} 為 null 代表請求省略了發生時間、交由伺服器決定：
     * 此時<strong>略過時間維度</strong>，伺服器首次決定的時間成立。若不如此，省略時間的請求
     * 每次重試都會得到不同的「現在」，於是每一次合法重試都變成假性 409。</p>
     *
     * @param existing            帳本中既有的分錄（以同一個冪等鍵查出）
     * @param type                本次請求的方向
     * @param currency            本次請求的幣別
     * @param amount              本次請求的金額
     * @param requestedOccurredAt 本次請求<strong>明確送出</strong>的發生時間（已截到微秒）；未送為 null
     * @param requestedReversalOfId 本次請求要沖銷的原分錄內部主鍵（由 service 以使用者範圍解析而來）；
     *                            一般分錄為 null。與既有分錄的 {@code reversalOfId} 以 {@link Objects#equals} 比對：
     *                            兩者皆 null 視為相同，一個為 null 另一個不是則不同（同 key 換沖銷對象或
     *                            在沖銷與一般分錄間切換都是不同的意圖）
     * @return 受比對的欄位全部相同時為 true；任一不同時為 false
     */
    public static boolean matches(
        CashLedgerEntry existing,
        CashEntryType type,
        CurrencyCode currency,
        BigDecimal amount,
        OffsetDateTime requestedOccurredAt,
        Long requestedReversalOfId
    ) {
        return existing.type() == type
            && existing.currency() == currency
            && Objects.equals(existing.reversalOfId(), requestedReversalOfId)
            && TradePayloadMatcher.sameAmount(existing.amount(), amount)
            && (requestedOccurredAt == null
                || TradePayloadMatcher.sameInstant(existing.occurredAt(), requestedOccurredAt));
    }
}
