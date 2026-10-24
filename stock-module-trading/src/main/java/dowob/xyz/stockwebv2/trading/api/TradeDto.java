package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 交易紀錄的對外表示。
 *
 * <p>{@code currency} 是標的的交易幣別（{@code CurrencyCode} 常數名，如 {@code USD}、{@code TWD}），
 * {@code price}、{@code fee} 等金額欄位皆以此幣別計價。幣別經 {@code AssetFacade#findProfiles}
 * 取得，trading 的 SQL 不 JOIN {@code assets}（D-21、D-28）；理論上標的必有 profile，
 * 若查無則為 {@code null}，前端須容忍。</p>
 *
 * @param id         交易 UUID
 * @param symbol     標的代號
 * @param type       交易類型 BUY / SELL
 * @param quantity   成交數量
 * @param price      成交單價（標的交易幣別）
 * @param fee        手續費（標的交易幣別）
 * @param note       備註；可為 null
 * @param executedAt 成交時間
 * @param createdAt  入帳時間
 * @param currency   標的交易幣別；查無 profile 時為 null
 * @author Yuan
 * @version 1.1
 */
public record TradeDto(
    String id,
    String symbol,
    String type,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal fee,
    String note,
    OffsetDateTime executedAt,
    OffsetDateTime createdAt,
    String currency
) {
}
