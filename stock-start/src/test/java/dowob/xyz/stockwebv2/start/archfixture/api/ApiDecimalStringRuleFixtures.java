package dowob.xyz.stockwebv2.start.archfixture.api;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ApiDecimalStringRulesTest}（D-09）自我驗證用的 fixture 持有類別。
 *
 * <p><strong>只供 {@code ApiDecimalStringRulesTest} 自我驗證，刻意違規，不是 API。</strong>這些型別放在 test 原始碼的
 * {@code ..archfixture.api..} 套件，套件名符合 {@code dowob.xyz.stockwebv2..api..}，才能被 D-09 的
 * 「API 傳輸型別」predicate 選中；正式的 {@code @ArchTest} 以 {@code DoNotIncludeTests} 匯入，看不到這裡，
 * 只有自我驗證測試以 {@code ClassFileImporter#importClasses} 明確匯入。</p>
 *
 * <p><strong>不得</strong>加任何 Spring stereotype（避免 {@code @SpringBootTest} 元件掃描撿到），巢狀型別名稱
 * 也不得以 {@code Test} / {@code IT} / {@code E2E} 結尾（避免被 Surefire / Failsafe / E2E 命名守門當成測試）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ApiDecimalStringRuleFixtures {

    private ApiDecimalStringRuleFixtures() {
    }

    /**
     * 違規：{@code *Response} 字尾、{@code BigDecimal} 欄位未標 {@link DecimalString}（舊 predicate 只看 Dto / Request 而漏掉）。
     *
     * @param amount 未標註的金額
     */
    public record UnannotatedAmountResponse(BigDecimal amount) {
    }

    /**
     * 違規：{@code *View} 字尾、{@code BigDecimal} 欄位未標 {@link DecimalString}。
     *
     * @param amount 未標註的金額
     */
    public record UnannotatedAmountView(BigDecimal amount) {
    }

    /**
     * 違規：沒有任何字尾的 record（模擬 controller 回傳的巢狀 record），{@code BigDecimal} 欄位未標 {@link DecimalString}。
     *
     * @param price 未標註的價格
     */
    public record UnannotatedPoint(BigDecimal price) {
    }

    /**
     * 合規：{@code BigDecimal} 欄位已標 {@link DecimalString}；{@code int} 與 {@code List<String>} 不涉及 {@code BigDecimal}。
     *
     * @param amount 已標註的金額
     * @param count  計數（JSON number）
     * @param tags   標籤
     */
    public record CompliantResponse(@DecimalString BigDecimal amount, int count, List<String> tags) {
    }

    /**
     * 違規（規則 2）:{@code List<BigDecimal>} —— {@link DecimalString} 不能標在 type-use 位置，元素會以 JSON number 輸出。
     *
     * @param amounts 參數化型別中的金額
     */
    public record ListAmountDto(List<BigDecimal> amounts) {
    }

    /**
     * 違規（規則 2）:{@code Map<String, BigDecimal>}。
     *
     * @param amountsByCurrency 參數化型別中的金額
     */
    public record MapAmountDto(Map<String, BigDecimal> amountsByCurrency) {
    }

    /**
     * 違規（規則 2）:{@code Optional<BigDecimal>}。
     *
     * @param amount 參數化型別中的金額
     */
    public record OptionalAmountDto(Optional<BigDecimal> amount) {
    }

    /**
     * 違規（規則 2）:{@code BigDecimal[]} —— raw type 不是 {@code BigDecimal}，規則 1 看不到。
     *
     * @param amounts 陣列中的金額
     */
    public record ArrayAmountDto(BigDecimal[] amounts) {
    }

    /**
     * 違規（規則 3）:沒有 {@code BigDecimal} 欄位，但 public getter 回傳未標註的 {@code BigDecimal}，Jackson 會把它序列化成
     * JSON number；只看欄位的規則 1 抓不到。
     */
    public static final class GetterResponse {

        /** 以最小單位保存的金額。 */
        private final long cents = 12_345L;

        /**
         * 回傳金額（未標註，刻意違規）。
         *
         * @return 金額
         */
        public BigDecimal getTotal() {
            return BigDecimal.valueOf(cents, 2);
        }
    }

    /**
     * 合規（規則 3）:同 {@link GetterResponse}，但 getter 標 {@link JsonIgnore}，不會被序列化，不需 {@link DecimalString}。
     */
    public static final class IgnoredGetterResponse {

        /** 以最小單位保存的金額。 */
        private final long cents = 12_345L;

        /**
         * 回傳金額（{@code @JsonIgnore}，不進 JSON）。
         *
         * @return 金額
         */
        @JsonIgnore
        public BigDecimal getTotal() {
            return BigDecimal.valueOf(cents, 2);
        }
    }

    /**
     * 範圍反例：非 record、名稱沒有 Dto / Request / Response / View 字尾的一般類別（模擬 {@code marketdata/api}
     * 內的 service），帶 {@code BigDecimal} 欄位也不屬於 API 傳輸型別，不得被規則選中。
     */
    public static final class PricingHelper {

        /** 內部計算用的值，不會被序列化。 */
        private final BigDecimal factor = BigDecimal.ONE;

        /**
         * 套用係數。
         *
         * @param value 輸入值
         * @return 乘上係數後的值
         */
        BigDecimal apply(BigDecimal value) {
            return value.multiply(factor);
        }
    }
}
