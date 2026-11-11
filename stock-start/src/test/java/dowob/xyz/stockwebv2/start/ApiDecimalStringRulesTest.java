package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.math.BigDecimal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

/**
 * API DTO / Request 的 {@link BigDecimal} 欄位字串化守門（Phase 5 D-05 / D-06 / D-09）。
 *
 * <p><strong>規則</strong>：位於 {@code dowob.xyz.stockwebv2..api..} 且 simple name 以 {@code Dto} 或
 * {@code Request} 結尾的類別（含巢狀 record）中，raw type 為 {@code BigDecimal} 的欄位必須標
 * {@link DecimalString}。判斷依據是 Java 型別（D-06）：沒有「這個欄位是比例所以可以是 number」的例外清單；
 * {@code Integer} / {@code int} / {@code long} / {@code double} 欄位不受影響，維持 JSON number。</p>
 *
 * <p><strong>為什麼是逐欄位標註 + 守門，不是全域 Jackson 設定</strong>：全域
 * {@code JsonMapperBuilderCustomizer} 定義在 {@code stock-start}，模組的 {@code @WebMvcTest} slice 拿不到，
 * 測試與正式環境會分歧，且會連帶改動 Redis 快取等非 API 的 JSON（RESEARCH §Q2 方案 C）。逐欄位標註的代價是
 * 可能漏標 —— 漏標的欄位會以 JSON number（瀏覽器端 double）輸出，大額被無聲改寫（T-05-24），本規則就是擋這個。</p>
 *
 * <p><strong>{@code allowEmptyShould(false)}</strong>：若 predicate 打錯導致一個欄位都沒選到，規則會紅，
 * 避免守門靜默失效。record component 上的標註因 {@code FIELD} target 傳播到 private field，ArchUnit 讀 bytecode 可見。</p>
 *
 * <p><strong>不涵蓋</strong>：Kafka 事件（如 {@code dowob.xyz.stockwebv2.common.event.PriceTickEvent}）不在
 * {@code ..api..} 套件，走 Jackson 2，不適用 {@link DecimalString}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AnalyzeClasses(
    packages = "dowob.xyz.stockwebv2",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ApiDecimalStringRulesTest {

    /**
     * 「API 傳輸型別」:{@code ..api..} 套件內、simple name 以 {@code Dto} 或 {@code Request} 結尾的類別。
     */
    private static final DescribedPredicate<JavaClass> API_TRANSFER_TYPES = new DescribedPredicate<>(
        "reside in package 'dowob.xyz.stockwebv2..api..' and have simple name ending with 'Dto' or 'Request'"
    ) {
        @Override
        public boolean test(JavaClass javaClass) {
            String simpleName = javaClass.getSimpleName();
            return JavaClass.Predicates.resideInAPackage("dowob.xyz.stockwebv2..api..").test(javaClass)
                && (simpleName.endsWith("Dto") || simpleName.endsWith("Request"));
        }
    };

    /**
     * D-09:API DTO / Request 的 {@code BigDecimal} 欄位必須標 {@link DecimalString}。
     */
    @ArchTest
    static final ArchRule apiDtoBigDecimalFieldsAreDecimalStrings = fields()
        .that().haveRawType(BigDecimal.class)
        .and().areDeclaredInClassesThat(API_TRANSFER_TYPES)
        .should().beAnnotatedWith(DecimalString.class)
        .allowEmptyShould(false);
}
