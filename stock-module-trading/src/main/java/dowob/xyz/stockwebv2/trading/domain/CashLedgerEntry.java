package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 現金分錄帳本的一列（{@code cash_ledger_entries}），append-only。
 *
 * @param id             資料庫主鍵；尚未寫入時為 null
 * @param uuid           對外識別碼；寫入時為 null 可由資料庫產生
 * @param userId         分錄擁有者
 * @param type           入金或出金
 * @param currency       幣別
 * @param amount         金額，恆為正
 * @param note           備註；無則為 null
 * @param occurredAt     分錄發生時間（使用者可補登）
 * @param createdAt      寫入時間；尚未寫入時為 null
 * @param idempotencyKey 冪等鍵，僅供請求層比對，不對外輸出
 * @param reversalOfId   被沖銷的原分錄主鍵；一般分錄為 null
 * @author Yuan
 * @version 1.0
 */
public record CashLedgerEntry(
    Long id,
    UUID uuid,
    Long userId,
    CashEntryType type,
    CurrencyCode currency,
    BigDecimal amount,
    String note,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt,
    String idempotencyKey,
    Long reversalOfId
) {
}
