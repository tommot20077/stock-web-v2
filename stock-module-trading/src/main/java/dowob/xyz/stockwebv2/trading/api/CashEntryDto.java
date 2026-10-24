package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 現金分錄的對外表示。
 *
 * <p><strong>不含 idempotencyKey</strong>：冪等鍵是請求層的實作細節，輸出它只會多一個洩漏面（T-04-09）。
 * {@code id} 是分錄的 UUID，而非資料庫自增主鍵。{@code amount} 以 JSON number 輸出，
 * 不加 {@code ToStringSerializer}，與既有交易 / 持倉 DTO 一致。</p>
 *
 * @param id                   分錄 UUID 字串
 * @param type                 DEPOSIT 或 WITHDRAWAL
 * @param currency             幣別
 * @param amount               金額，恆為正
 * @param note                 備註；無則為 null
 * @param occurredAt           分錄發生時間
 * @param createdAt            寫入時間
 * @param reversalOfId         被沖銷的原分錄 UUID 字串；一般分錄為 null
 * @param reversalOfOccurredAt 被沖銷的原分錄發生時間；一般分錄為 null
 * @param reversed             此分錄是否已被沖銷
 * @author Yuan
 * @version 1.0
 */
public record CashEntryDto(
    String id,
    String type,
    String currency,
    BigDecimal amount,
    String note,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt,
    String reversalOfId,
    OffsetDateTime reversalOfOccurredAt,
    boolean reversed
) {
}
