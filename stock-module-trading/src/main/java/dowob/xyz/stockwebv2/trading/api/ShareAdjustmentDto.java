package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 股數調整紀錄的對外表示（ADJ-01）。
 *
 * <p><strong>不含冪等鍵</strong>，也不含內部的 {@code effective_at}：對外以使用者輸入的除權日表達。
 * {@code id} 是紀錄的 UUID，而非資料庫自增主鍵。</p>
 *
 * @param id            紀錄 UUID 字串
 * @param symbol        標的代號
 * @param currency      標的交易幣別
 * @param type          {@code STOCK_DIVIDEND} 或 {@code SPLIT}
 * @param quantityDelta 後端算出的股數變動，帶號（合股為負）；沖銷列輸出原值
 * @param ratioNew      比例分子，僅 {@code SPLIT}
 * @param ratioOld      比例分母，僅 {@code SPLIT}
 * @param effectiveDate 除權日
 * @param createdAt     寫入時間
 * @param note          備註；無則為 null
 * @param reversalOfId  被沖銷的原紀錄 UUID 字串；一般紀錄為 null
 * @param reversed      此紀錄是否已被沖銷
 * @author Yuan
 * @version 1.0
 */
public record ShareAdjustmentDto(
    String id,
    String symbol,
    String currency,
    String type,
    @DecimalString BigDecimal quantityDelta,
    Integer ratioNew,
    Integer ratioOld,
    LocalDate effectiveDate,
    OffsetDateTime createdAt,
    String note,
    String reversalOfId,
    boolean reversed
) {
}
