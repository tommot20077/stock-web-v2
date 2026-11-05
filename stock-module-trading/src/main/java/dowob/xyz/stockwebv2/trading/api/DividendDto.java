package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 現金股利紀錄的對外表示（DIV-01）。
 *
 * <p><strong>不含冪等鍵</strong>：冪等鍵是請求層的實作細節，輸出它只會多一個洩漏面。
 * {@code id} 是紀錄的 UUID，而非資料庫自增主鍵。金額以 JSON number 輸出，與既有 DTO 一致。</p>
 *
 * @param id             紀錄 UUID 字串
 * @param symbol         標的代號
 * @param currency       標的交易幣別（{@code amount} / {@code withholdingTax} 的幣別）
 * @param amount         實領總額，恆為正（沖銷列亦同，以 {@code reversalOfId} 判斷方向）
 * @param withholdingTax 預扣稅，≥ 0
 * @param paidAt         入帳時間
 * @param createdAt      寫入時間
 * @param note           備註；無則為 null
 * @param reversalOfId   被沖銷的原紀錄 UUID 字串；一般紀錄為 null
 * @param reversed       此紀錄是否已被沖銷
 * @author Yuan
 * @version 1.0
 */
public record DividendDto(
    String id,
    String symbol,
    String currency,
    BigDecimal amount,
    BigDecimal withholdingTax,
    OffsetDateTime paidAt,
    OffsetDateTime createdAt,
    String note,
    String reversalOfId,
    boolean reversed
) {
}
