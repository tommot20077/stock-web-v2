package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 現金分錄的對外表示。
 *
 * <p><strong>不含冪等鍵</strong>：冪等鍵是請求層的實作細節，輸出它只會多一個洩漏面（T-04-09）。
 * {@code id} 是分錄的 UUID，而非資料庫自增主鍵。</p>
 *
 * <p>{@code amount} 以 JSON 字串（一般記號、保留 scale，例如 {@code "50000.00000000"}）輸出，
 * 見 {@link DecimalString}（Phase 5 D-05 / D-09）：JSON number 在瀏覽器端是 double，大額會被無聲改寫。</p>
 *
 * @param id                   分錄 UUID 字串
 * @param type                 DEPOSIT 或 WITHDRAWAL
 * @param currency             幣別
 * @param amount               金額，恆為正；序列化為 JSON 字串
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
    @DecimalString BigDecimal amount,
    String note,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt,
    String reversalOfId,
    OffsetDateTime reversalOfOccurredAt,
    boolean reversed
) {
}
