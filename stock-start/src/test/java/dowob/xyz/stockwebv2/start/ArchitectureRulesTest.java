package dowob.xyz.stockwebv2.start;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架構規則自動掃描（testing-standards.md §ArchUnit Testing Requirements）。
 *
 * <p>本測試置於 stock-start（L3），其測試 classpath 涵蓋全部模組，故可一次掃描整個專案。
 * 掃描範圍排除測試類別，僅檢查正式程式碼。</p>
 *
 * <p><b>目前實作 4 條規則（憲法列出 5 條）。</b>未實作的是「Service 層方法必須呼叫
 * {@code SecurityUtils.assertOwnerOrAdmin}」一條，理由如下（刻意延後，非遺漏）：</p>
 * <ul>
 *   <li>{@code SecurityUtils.assertOwnerOrAdmin} 目前並不存在於程式碼中；</li>
 *   <li>現行 ownership 以查詢範圍限定（{@code where user_id = :userId}）達成，非擁有者取得 404，
 *       功能上已安全；</li>
 *   <li>改採 fetch-then-assert 會一併引入目前<b>不存在</b>的 ADMIN 繞過語意——那是安全行為變更，
 *       應作為獨立決策處理，不宜作為新增 lint 規則的副作用。</li>
 * </ul>
 * <p>該條規則與 {@code SecurityUtils} 的導入應另案評估後補上。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AnalyzeClasses(
    packages = "dowob.xyz.stockwebv2",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    /**
     * 規則 1：模組隔離 — L2 業務模組之間不得直接相依，跨模組互動一律經由
     * stock-infrastructure（L1）的 Facade 介面（architecture.md §Facade Pattern）。
     */
    @ArchTest
    static final SliceRule l2ModulesMustNotDependOnEachOther = SlicesRuleDefinition.slices()
        .matching("dowob.xyz.stockwebv2.(user|asset|trading|backtest|marketdata)..")
        .should().notDependOnEachOther();

    /**
     * 規則 2：Facade 邊界 — Facade 介面必須定義於 stock-infrastructure（L1），
     * 不得散落於各 L2 模組內（architecture.md：「Facade interfaces defined in stock-infrastructure」）。
     */
    @ArchTest
    static final ArchRule facadeInterfacesMustLiveInInfrastructure = classes()
        .that().areInterfaces().and().haveSimpleNameEndingWith("Facade")
        .should().resideInAPackage("dowob.xyz.stockwebv2.infrastructure..");

    /**
     * 規則 3：DDD 分層 — Controller 不得直接相依 Repository，必須經由 Service 層
     * （architecture.md §DDD-Lite）。
     *
     * <p>限定於本專案自有的 repository 套件；框架自身位於 {@code ...repository...} 的類別
     * （如 Spring Batch 的 {@code JobExplorer}）不在此規則約束範圍。</p>
     */
    @ArchTest
    static final ArchRule controllersMustNotDependOnRepositories = noClasses()
        .that().areAnnotatedWith(RestController.class)
        .should().dependOnClassesThat().resideInAPackage("dowob.xyz.stockwebv2..repository..");

    /**
     * 規則 5：Controller 不得直接呼叫 Facade，必須經由 Application Service
     * （architecture.md §Cross-Module Communication：「Facade interfaces may only be called by
     * Application Services — Controllers must NOT call them directly」）。
     */
    @ArchTest
    static final ArchRule controllersMustNotDependOnFacades = noClasses()
        .that().areAnnotatedWith(RestController.class)
        .should().dependOnClassesThat().haveSimpleNameEndingWith("Facade");
}
