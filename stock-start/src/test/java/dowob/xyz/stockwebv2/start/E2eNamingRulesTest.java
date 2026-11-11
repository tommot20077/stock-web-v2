package dowob.xyz.stockwebv2.start;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 後端 E2E 套件命名守門(D-23 / D-27)。
 *
 * <p><strong>為什麼命名需要守門:</strong>本專案以測試類別的後綴決定由哪個 Maven 執行者跑
 * (testing-standards.md 測試分層表):{@code *Test} → 預設 Surefire(CI Unit job,不保證有 Docker)、
 * {@code *IT} → Failsafe(Integration job)、{@code *E2E} → {@code -Pe2e} profile 的 Surefire
 * (CI E2E job)。{@code stock-start/pom.xml} 的預設 Surefire 以 {@code *E2E.java} 樣式 exclude
 * E2E,{@code e2e} profile 以同一樣式 include、只收 E2E。
 * 若 {@code e2e} 套件內出現 {@code *E2ETest} 這類以 {@code Test} 結尾的類別,它會被 Unit job 執行
 * (需要容器、無 Docker 時 error),卻不會被 E2E job 執行 —— 這正是 D-23 修正的 7 個既有違規。</p>
 *
 * <p>本類別位於 {@code start} 套件、{@code e2e} 套件之外,且以 {@code Test} 結尾,
 * 因此由 Unit job 執行,不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AnalyzeClasses(
    packages = "dowob.xyz.stockwebv2.start.e2e",
    importOptions = ImportOption.OnlyIncludeTests.class
)
class E2eNamingRulesTest {

    /**
     * 規則 1:{@code e2e} 套件(含 {@code support} 子套件)內<strong>任何</strong>類別都不得以
     * {@code Test} 結尾(D-23)。
     *
     * <p><strong>不豁免 abstract 類別(D-27):</strong>抽象基底雖然不會被 Surefire 執行,但名稱
     * {@code Abstract*E2ETest} 會讓子類別照抄 {@code *E2ETest} 命名,形成回歸來源;規則一律禁止,
     * 守門沒有例外清單。{@code allowEmptyShould(false)}:套件掃描為空(例如套件改名)時直接失敗,
     * 避免規則空轉成假綠。</p>
     */
    @ArchTest
    static final ArchRule noClassesInE2ePackageEndWithTest = noClasses()
        .that().resideInAPackage("dowob.xyz.stockwebv2.start.e2e..")
        .should().haveSimpleNameEndingWith("Test")
        .allowEmptyShould(false);

    /**
     * 規則 2:{@code e2e} 根套件內的具體頂層類別必須以 {@code E2E} 結尾,才會被 {@code -Pe2e} 收進
     * E2E job(D-23)。
     *
     * <p>只檢查根套件:{@code support} 子套件放的是基底類別與 helper(如 {@code AuthE2EHelper}、
     * {@code DatabaseCleaner}),不是可執行的測試。排除 abstract 只是因為抽象基底本來就不會被執行,
     * 不是命名豁免 —— 抽象基底的 {@code Test} 後綴仍由規則 1 禁止。</p>
     */
    @ArchTest
    static final ArchRule concreteE2eClassesEndWithE2E = classes()
        .that().resideInAPackage("dowob.xyz.stockwebv2.start.e2e")
        .and().areTopLevelClasses()
        .and().doNotHaveModifier(JavaModifier.ABSTRACT)
        .should().haveSimpleNameEndingWith("E2E")
        .allowEmptyShould(false);
}
