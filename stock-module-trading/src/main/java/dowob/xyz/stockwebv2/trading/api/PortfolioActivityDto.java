package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 統一活動列表（{@code GET /api/v1/portfolio/activities}，PORT-13）的一列。
 *
 * <p>欄位逐一對應 {@code ai-docs/trading-portfolio-contract.md} 的〈PortfolioActivityDto〉一節；
 * 不適用於該 kind 的欄位恆為 null（契約〈各 kind 欄位適用表〉）。沖銷列的數值欄位輸出<strong>原值</strong>
 * （不反號），前端以 {@code reversalOfId != null} 判斷。不含冪等鍵與資料庫自增主鍵。</p>
 *
 * @param id                      原紀錄 UUID（交易 / 股利 / 股數調整各自的 UUID）
 * @param kind                    {@code BUY} / {@code SELL} / {@code CASH_DIVIDEND} / {@code STOCK_DIVIDEND} / {@code SPLIT}
 * @param symbol                  標的代號；資產屬性缺失時為 null
 * @param currency                標的交易幣別；資產屬性缺失時為 null
 * @param occurredAt              交易成交時間 / 股利入帳時間 / 股數調整生效瞬間（{@code effective_at}）
 * @param effectiveDate           僅股數調整：除權日
 * @param createdAt               寫入時間
 * @param quantity                交易數量 / 配股股數 / 拆股 ΔQ（帶號）；股利為 null
 * @param price                   僅交易
 * @param fee                     僅交易
 * @param amount                  交易為 quantity × price；股利為實領總額；股數調整為 null
 * @param withholdingTax          僅股利
 * @param ratioNew                僅拆股
 * @param ratioOld                僅拆股
 * @param note                    備註
 * @param reversalOfId            沖銷列指向的原列 UUID
 * @param reversalOfOccurredAt    原列的 {@code occurredAt}
 * @param reversalOfEffectiveDate 原列的 {@code effectiveDate}（僅股數調整）
 * @param reversed                此列是否已被沖銷（交易恆為 false）
 * @param reversible              交易恆 false；股利 = 非沖銷列且未被沖銷；股數調整 = 同上且為該標的最晚一筆未沖銷調整
 * @author Yuan
 * @version 1.0
 */
public record PortfolioActivityDto(
    String id,
    String kind,
    String symbol,
    String currency,
    OffsetDateTime occurredAt,
    LocalDate effectiveDate,
    OffsetDateTime createdAt,
    @DecimalString BigDecimal quantity,
    @DecimalString BigDecimal price,
    @DecimalString BigDecimal fee,
    @DecimalString BigDecimal amount,
    @DecimalString BigDecimal withholdingTax,
    Integer ratioNew,
    Integer ratioOld,
    String note,
    String reversalOfId,
    OffsetDateTime reversalOfOccurredAt,
    LocalDate reversalOfEffectiveDate,
    boolean reversed,
    boolean reversible
) {
}
