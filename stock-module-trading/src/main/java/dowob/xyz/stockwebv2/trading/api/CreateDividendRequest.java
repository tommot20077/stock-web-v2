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
 * 記一筆現金股利的請求（DIV-01，契約見 {@code ai-docs/trading-portfolio-contract.md}）。
 *
 * <p>金額上限 1e12、小數鎖 8 位的理由同 {@link CreateCashEntryRequest}：上限遠低於 double 可精確表示的整數，
 * 小數超過 8 位會被 {@code NUMERIC(24,8)} 靜默捨入，讓合法重送吃到假性的冪等衝突。
 * <strong>沒有幣別欄位</strong>：幣別一律取自標的。</p>
 *
 * <p>{@code reversalOfId} 以 {@code String} 承接（只限長度 36）而不是 {@link java.util.UUID}：格式錯誤要由 service
 * 以 {@code fields.reversalOfId = "invalid format"} 指名回 400，若交給 JSON 反序列化會變成無 fields 的通用 400。</p>
 *
 * @param symbol         標的代號，大小寫不敏感；須為 active 且 tradeable 的標的
 * @param amount         實領總額（扣稅後、標的幣別），恆為正
 * @param withholdingTax 預扣稅（標的幣別），選填，省略視為 0
 * @param paidAt         入帳時間，選填；省略代表伺服器現在
 * @param note           備註，選填，最長 500 字元；不納入冪等 payload 比對
 * @param reversalOfId   要沖銷的原紀錄 UUID，選填；帶值代表這是一筆沖銷，symbol / amount / withholdingTax 須與原列相同
 * @author Yuan
 * @version 1.1
 */
public record CreateDividendRequest(
    @NotBlank @Size(max = 32) String symbol,
    @NotNull @DecimalMin(value = "0.00000001") @DecimalMax(value = "1000000000000")
    @Digits(integer = 13, fraction = 8) BigDecimal amount,
    @DecimalMin(value = "0") @DecimalMax(value = "1000000000000")
    @Digits(integer = 13, fraction = 8) BigDecimal withholdingTax,
    OffsetDateTime paidAt,
    @Size(max = 500) String note,
    @Size(max = 36) String reversalOfId
) {
}
