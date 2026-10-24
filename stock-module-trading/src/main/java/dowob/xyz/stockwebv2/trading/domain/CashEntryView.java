package dowob.xyz.stockwebv2.trading.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 現金分錄的讀取視圖：一列分錄加上列表需要、但單看該列算不出來的衍生資訊。
 *
 * <p>{@code CashLedgerEntry#reversalOfId} 只是原分錄的內部主鍵，而對外契約要的是原分錄的 UUID 與發生時間
 * （前端不需自行推導，UI-SPEC U-18）；{@code reversed} 則要看有沒有<strong>別的</strong>分錄指向本列。
 * 這三項都由 repository 在同一道查詢內算出，讓 service 不必為每一列再補查。</p>
 *
 * @param entry                分錄本身
 * @param reversalOfUuid       本列若為沖銷分錄，其原分錄的 UUID；一般分錄為 null
 * @param reversalOfOccurredAt 本列若為沖銷分錄，其原分錄的發生時間；一般分錄為 null
 * @param reversed             本列是否已被另一筆沖銷分錄沖銷
 * @author Yuan
 * @version 1.0
 */
public record CashEntryView(
    CashLedgerEntry entry,
    UUID reversalOfUuid,
    OffsetDateTime reversalOfOccurredAt,
    boolean reversed
) {
}
