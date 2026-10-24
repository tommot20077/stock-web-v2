package dowob.xyz.stockwebv2.trading.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 建立入金 / 出金分錄的請求（CASH-01）。
 *
 * <p><strong>amount 上限 1e12</strong>：JSON number 在瀏覽器端是 double，1e12 對 JPY / TWD 的大額已足夠，
 * 又遠低於 double 能精確表示整數的 2^53（約 9e15），前端不會在顯示時悄悄失真。
 * <strong>小數鎖 8 位</strong>：{@code NUMERIC(24,8)} 會把第 9 位靜默捨入，存進去的值與請求值不同，
 * 同一把冪等鍵的合法重試在 payload 比對時就會吃到假性 409（與 {@link CreateTradeRequest#fee()} 同理）。</p>
 *
 * <p>{@code type} 與 {@code currency} 刻意用 String 而非列舉：Jackson 反序列化列舉失敗會變成沒有
 * {@code fields} 的籠統 400，用 String 才能在 service 層以 {@code fields} 指名出錯的欄位。</p>
 *
 * @param type       DEPOSIT 或 WITHDRAWAL，大小寫不敏感
 * @param currency   USD / TWD / EUR / JPY，大小寫不敏感
 * @param amount     金額，恆為正
 * @param note       備註，選填，最長 500 字元；不納入冪等 payload 比對
 * @param occurredAt 分錄發生時間，選填；省略代表伺服器現在
 * @author Yuan
 * @version 1.0
 */
public record CreateCashEntryRequest(
    @NotBlank String type,
    @NotBlank String currency,
    @NotNull @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000000000")
    @Digits(integer = 13, fraction = 8) BigDecimal amount,
    @Size(max = 500) String note,
    OffsetDateTime occurredAt
) {
}
