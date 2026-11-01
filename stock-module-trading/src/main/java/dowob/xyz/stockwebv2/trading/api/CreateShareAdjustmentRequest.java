package dowob.xyz.stockwebv2.trading.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 記一筆配股或拆股 / 合股的請求（ADJ-01，契約見 {@code ai-docs/trading-portfolio-contract.md}）。
 *
 * <p>依 {@code type} 而必填的欄位（配股的 {@code quantity}、拆股的 {@code ratioNew} / {@code ratioOld}）
 * 在 service 層驗證，這裡只鎖範圍。另一種 type 的欄位若有送出會被忽略（不寫入、不納入冪等比對）。
 * {@code quantityDelta} 由後端計算，<strong>不在請求中</strong>。</p>
 *
 * <p>本版<strong>刻意不含 {@code reversalOfId}</strong>：沖銷由 04.2-08 一次加入請求欄位與完整流程，
 * 避免出現「收得到參數卻不處理」的半套行為。</p>
 *
 * @param symbol        標的代號，大小寫不敏感；須為 active 且 tradeable 的標的
 * @param type          {@code STOCK_DIVIDEND} 或 {@code SPLIT}，大小寫不敏感
 * @param quantity      配股實領股數（{@code STOCK_DIVIDEND} 必填）：0.00000001 ~ 1,000,000,000，小數最多 8 位
 * @param ratioNew      比例分子 new（{@code SPLIT} 必填）：1 ~ 1000
 * @param ratioOld      比例分母 old（{@code SPLIT} 必填）：1 ~ 1000，不得等於 ratioNew
 * @param effectiveDate 除權日（該標的市場的日曆日，{@code yyyy-MM-dd}）
 * @param note          備註，選填，最長 500 字元；不納入冪等 payload 比對
 * @author Yuan
 * @version 1.0
 */
public record CreateShareAdjustmentRequest(
    @NotBlank @Size(max = 32) String symbol,
    @NotBlank @Size(max = 20) String type,
    @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000000")
    @Digits(integer = 10, fraction = 8) BigDecimal quantity,
    @Min(1) @Max(1000) Integer ratioNew,
    @Min(1) @Max(1000) Integer ratioOld,
    @NotNull LocalDate effectiveDate,
    @Size(max = 500) String note
) {
}
