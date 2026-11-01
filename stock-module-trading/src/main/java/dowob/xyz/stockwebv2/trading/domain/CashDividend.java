package dowob.xyz.stockwebv2.trading.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 現金股利帳本的一列（{@code cash_dividends}），append-only（D-13）。
 *
 * <p>股利不動股數與平均成本（D-01），只影響可用現金與 summary 的股利收入。
 * <strong>不含幣別</strong>：幣別一律是標的的交易幣別，經 {@code AssetFacade} 取得，避免存一份可能過時的副本。
 * 沖銷列存與原列<strong>相同的正值</strong>，方向由 {@code reversalOfId != null} 表達，聚合時須反號。</p>
 *
 * @param id             資料庫主鍵；尚未寫入時為 null
 * @param uuid           對外識別碼；寫入時為 null 可由資料庫產生
 * @param userId         紀錄擁有者
 * @param assetId        標的主鍵（跨模組識別，屬性經 Facade 取得）
 * @param amount         實領總額（扣稅後、標的幣別），恆為正
 * @param withholdingTax 預扣稅（標的幣別），≥ 0；未填為 0
 * @param note           備註；無則為 null
 * @param paidAt         入帳時間（使用者可補登）
 * @param createdAt      寫入時間；尚未寫入時為 null
 * @param idempotencyKey 冪等鍵，僅供請求層比對，不對外輸出
 * @param reversalOfId   被沖銷的原紀錄主鍵；一般紀錄為 null
 * @author Yuan
 * @version 1.0
 */
public record CashDividend(
    Long id,
    UUID uuid,
    Long userId,
    Long assetId,
    BigDecimal amount,
    BigDecimal withholdingTax,
    String note,
    OffsetDateTime paidAt,
    OffsetDateTime createdAt,
    String idempotencyKey,
    Long reversalOfId
) {
}
