package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 建立回測的請求本體（{@code POST /api/v1/backtests/runs}）。
 *
 * <p><strong>{@code initialCapital} 的範圍</strong>（BE-CR-01 / T-05-37，Yuan 2026-11-14 裁決，前後端一致）：</p>
 * <ul>
 *   <li><strong>上限 1e12、integer 13 位</strong>：產品上限，與 trading 模組的金額上限
 *       （{@code CreateCashEntryRequest} 的 {@code @DecimalMax("1000000000000")}）一致；
 *       引擎 60 個月最多約 1.31 倍，V3 {@code initial_capital NUMERIC(24,6)} 仍有餘裕。</li>
 *   <li><strong>最多 6 位小數</strong>：對齊 V3 {@code NUMERIC(24,6)} 與引擎的 {@code setScale(6)}；
 *       超過 6 位的極小值（如 {@code 0.0000001}）會被捨入成 0，造成 drawdown 計算除以 0 而回 500。
 *       注意 Hibernate Validator 的 {@code @Digits} 以 {@code BigDecimal} 的原始 {@code scale()} 計算，
 *       尾端零也算（{@code 100.00000000} 回 400）；前端送出前已去尾零（05-28），正式路徑不受影響。</li>
 *   <li><strong>為何必須在 Bean Validation 擋</strong>：{@code "1e999999999"} 這類指數字串可被
 *       {@code BigDecimal} 以 O(1) 表示，但引擎在持久化之前就會 {@code setScale} / {@code toPlainString}，
 *       把它展開成十億位數字而耗盡 CPU / heap。界外值必須在進入 service 之前回 400；
 *       {@code BacktestService} 另有同源的縱深檢查。</li>
 * </ul>
 *
 * @param strategyId     策略 id（preset 或 {@code custom}）
 * @param strategyCode   自訂策略程式碼，preset 策略忽略
 * @param symbol         資產代號，最多 32 字元（安全審查 L-1）
 * @param period         回測期間（{@code 1Y} / {@code 3Y} / {@code 5Y}）
 * @param initialCapital 初始資金，須大於 0、不超過 1e12、最多 6 位小數
 * @param currency       幣別
 * @param benchmark      對照基準
 * @param dataMode       資料來源模式
 * @author Yuan
 * @version 1.1
 */
public record CreateBacktestRunRequest(
    @NotBlank String strategyId,
    String strategyCode,
    @NotBlank @Size(max = 32) String symbol,
    @NotBlank String period,
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("1000000000000")
    @Digits(integer = 13, fraction = 6)
    @DecimalString
    BigDecimal initialCapital,
    @NotBlank String currency,
    @NotBlank String benchmark,
    @NotBlank String dataMode
) {
}
