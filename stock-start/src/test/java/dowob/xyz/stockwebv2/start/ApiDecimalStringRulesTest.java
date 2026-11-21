package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.ArrayAmountDto;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.CompliantResponse;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.GetterResponse;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.IgnoredGetterResponse;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.ListAmountDto;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.MapAmountDto;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.OptionalAmountDto;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.PricingHelper;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.UnannotatedAmountResponse;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.UnannotatedAmountView;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.UnannotatedPoint;
import dowob.xyz.stockwebv2.trading.api.CreateTradeRequest;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioActivityDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.math.BigDecimal;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

/**
 * API 傳輸型別的 {@link BigDecimal} 字串化守門（Phase 5 D-05 / D-06 / D-09，BE-WR-01）。
 *
 * <p><strong>API 傳輸型別</strong>：位於 {@code dowob.xyz.stockwebv2..api..}，且 simple name 以 {@code Dto} /
 * {@code Request} / {@code Response} / {@code View} 結尾，或本身是 record（含巢狀型別）。</p>
 *
 * <p><strong>規則 1</strong>：API 傳輸型別中 raw type 為 {@code BigDecimal} 的欄位必須標
 * {@link DecimalString}。判斷依據是 Java 型別（D-06）：沒有「這個欄位是比例所以可以是 number」的例外清單；
 * {@code Integer} / {@code int} / {@code long} / {@code double} 欄位不受影響，維持 JSON number。</p>
 *
 * <p><strong>規則 2</strong>：API 傳輸型別的欄位，以及 public、非 static、零參數方法（Jackson 會序列化的 getter /
 * record accessor 形態）的回傳型別，若型別簽章含 {@code BigDecimal} 但本身不是 {@code BigDecimal}
 * （{@code List<BigDecimal>}、{@code Map<String, BigDecimal>}、{@code Optional<BigDecimal>}、{@code BigDecimal[]}
 * 等）一律禁止：{@link DecimalString} 沒有 {@code TYPE_USE} target，這些位置無法標註，元素必然以 JSON number 輸出。
 * 需要集合時請包一層帶 {@link DecimalString} 欄位的 record。</p>
 *
 * <p><strong>規則 3</strong>：API 傳輸型別中 public、非 static、零參數、raw 回傳 {@code BigDecimal} 且未標
 * {@code @JsonIgnore} 的方法必須標 {@link DecimalString}。record accessor 由 component 上的標註（{@code METHOD}
 * target）傳播而滿足；擋的是「沒有 {@code BigDecimal} 欄位、只有 getter」這種規則 1 看不到的寫法。</p>
 *
 * <p><strong>為什麼是逐欄位標註 + 守門，不是全域 Jackson 設定</strong>：全域
 * {@code JsonMapperBuilderCustomizer} 定義在 {@code stock-start}，模組的 {@code @WebMvcTest} slice 拿不到，
 * 測試與正式環境會分歧，且會連帶改動 Redis 快取等非 API 的 JSON（RESEARCH §Q2 方案 C）。逐欄位標註的代價是
 * 可能漏標 —— 漏標的欄位會以 JSON number（瀏覽器端 double）輸出，大額被無聲改寫（T-05-24），本規則就是擋這個。</p>
 *
 * <p><strong>{@code allowEmptyShould(false)}</strong>：若 predicate 打錯導致一個欄位都沒選到，規則會紅，
 * 避免守門靜默失效。record component 上的標註因 {@code FIELD} target 傳播到 private field，ArchUnit 讀 bytecode 可見。</p>
 *
 * <p><strong>行為樣本</strong>：ArchUnit 只證明「有標」，下方 {@code @Test} 以不帶任何全域設定的
 * {@code JsonMapper.builder().build()} 證明「標了真的輸出字串」—— 特別是 {@code BigDecimal.ZERO.setScale(8)}
 * （{@code PortfolioQueryService} 的預設值）輸出 {@code "0.00000000"} 而不是 {@code 0E-8}；整數 / 計數欄位維持 JSON number。</p>
 *
 * <p><strong>自我驗證</strong>：test 原始碼的
 * {@code dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures} 放了刻意違規 / 合規的 fixture，
 * 下方 {@code selfCheck*} 測試以<strong>同一個</strong> {@link ArchRule} 物件 {@code evaluate} 這些 fixture，
 * 證明每條規則真的會紅（正式 {@code @ArchTest} 以 {@code DoNotIncludeTests} 匯入，看不到 fixture）。</p>
 *
 * <p><strong>不涵蓋</strong>：Kafka 事件（如 {@code dowob.xyz.stockwebv2.common.event.PriceTickEvent}）不在
 * {@code ..api..} 套件，走 Jackson 2，不適用 {@link DecimalString}；不在 {@code ..api..} 套件、或位於 {@code ..api..}
 * 但既非 record 也沒有上述字尾的型別；以反射、{@code @JsonAnyGetter}、自訂 serializer / {@code @JsonValue} 等
 * 繞過欄位 / getter 的輸出路徑。</p>
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
     * 「API 傳輸型別」（BE-WR-01 / D-09）:位於 {@code dowob.xyz.stockwebv2..api..}，且 simple name 以
     * {@code Dto} / {@code Request} / {@code Response} / {@code View} 結尾，<strong>或</strong>本身是 record。
     *
     * <p><strong>為什麼納入 record</strong>：controller 回傳的巢狀 record（如 {@code CsrfController.CsrfResponse}）
     * 不一定照字尾命名，只看字尾會讓沒有字尾的 payload record 繞過守門。</p>
     *
     * <p><strong>為什麼不是「{@code ..api..} 內全部類別」</strong>：{@code marketdata/api} 內有
     * {@code MarketDataFacadeImpl} / {@code MarketLatestService} / {@code KlineQueryService} 等 service，它們只有
     * {@code BigDecimal} 區域變數或注入的依賴，不是 payload；把它們納入只會製造假陽性。</p>
     */
    private static final DescribedPredicate<JavaClass> API_TRANSFER_TYPES = new DescribedPredicate<>(
        "reside in package 'dowob.xyz.stockwebv2..api..' and (have simple name ending with "
            + "'Dto' / 'Request' / 'Response' / 'View' or are records)"
    ) {
        @Override
        public boolean test(JavaClass javaClass) {
            String simpleName = javaClass.getSimpleName();
            return JavaClass.Predicates.resideInAPackage("dowob.xyz.stockwebv2..api..").test(javaClass)
                && (simpleName.endsWith("Dto")
                    || simpleName.endsWith("Request")
                    || simpleName.endsWith("Response")
                    || simpleName.endsWith("View")
                    || javaClass.isRecord());
        }
    };

    /**
     * 規則 1（D-09）:API 傳輸型別中 raw type 為 {@code BigDecimal} 的欄位必須標 {@link DecimalString}。
     */
    @ArchTest
    static final ArchRule apiTransferBigDecimalFieldsAreDecimalStrings = fields()
        .that().haveRawType(BigDecimal.class)
        .and().areDeclaredInClassesThat(API_TRANSFER_TYPES)
        .should().beAnnotatedWith(DecimalString.class)
        .allowEmptyShould(false);

    /**
     * 「型別簽章含 {@code BigDecimal} 但本身不是 {@code BigDecimal}」:參數化型別的型別參數（含巢狀、萬用字元上界）或陣列的
     * 元素型別為 {@code BigDecimal}。陣列以 {@code getBaseComponentType()} 展開，涵蓋 {@code BigDecimal[]} 與
     * {@code List<BigDecimal[]>}。
     */
    private static final DescribedPredicate<JavaType> HIDES_BIG_DECIMAL = new DescribedPredicate<>(
        "involve BigDecimal in its signature without being BigDecimal itself"
    ) {
        @Override
        public boolean test(JavaType type) {
            if (type.toErasure().isEquivalentTo(BigDecimal.class)) {
                return false;
            }
            return type.getAllInvolvedRawTypes().stream()
                .map(JavaClass::getBaseComponentType)
                .anyMatch(raw -> raw.isEquivalentTo(BigDecimal.class));
        }
    };

    /** Jackson 會當成 property 序列化的方法形態:public、非 static、零參數。 */
    private static final DescribedPredicate<JavaMethod> SERIALIZABLE_ACCESSOR_SHAPE = DescribedPredicate.describe(
        "are public, non-static and have no parameters",
        method -> method.getModifiers().contains(JavaModifier.PUBLIC)
            && !method.getModifiers().contains(JavaModifier.STATIC)
            && method.getRawParameterTypes().isEmpty()
    );

    /**
     * 規則 2（BE-WR-01 / D-09）:API 傳輸型別的欄位與 public 零參數非 static 方法，型別簽章不得「含 {@code BigDecimal}
     * 但本身不是 {@code BigDecimal}」。欄位與方法分屬兩種 member，以 {@link CompositeArchRule} 合成一條規則；
     * 兩邊的選取範圍都是「該類別的全部欄位 / 方法」，條件才檢查型別，因此選取不會因合規而為空。
     */
    @ArchTest
    static final ArchRule apiTransferTypesDoNotHideBigDecimalInParameterizedOrArrayTypes = CompositeArchRule
        .of(fields()
            .that().areDeclaredInClassesThat(API_TRANSFER_TYPES)
            .should(notHaveTypeHidingBigDecimal())
            .allowEmptyShould(false))
        .and(methods()
            .that().areDeclaredInClassesThat(API_TRANSFER_TYPES)
            .and(SERIALIZABLE_ACCESSOR_SHAPE)
            .should(notReturnTypeHidingBigDecimal())
            .allowEmptyShould(false));

    /**
     * 規則 3（BE-WR-01 / D-09）:API 傳輸型別中 public、非 static、零參數、raw 回傳 {@code BigDecimal} 且未標
     * {@link JsonIgnore} 的方法必須標 {@link DecimalString}（record accessor 由 component 標註傳播而滿足）。
     */
    @ArchTest
    static final ArchRule apiTransferBigDecimalAccessorsAreDecimalStrings = methods()
        .that().areDeclaredInClassesThat(API_TRANSFER_TYPES)
        .and(SERIALIZABLE_ACCESSOR_SHAPE)
        .and().haveRawReturnType(BigDecimal.class)
        .and().areNotAnnotatedWith(JsonIgnore.class)
        .should().beAnnotatedWith(DecimalString.class)
        .allowEmptyShould(false);

    /**
     * 規則 2 的欄位條件。
     *
     * @return 欄位型別不得把 {@code BigDecimal} 藏在型別參數或陣列裡
     */
    private static ArchCondition<JavaField> notHaveTypeHidingBigDecimal() {
        return new ArchCondition<>("not have a type that involves BigDecimal without being BigDecimal "
            + "(e.g. List / Map / Optional type arguments or BigDecimal[])") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (HIDES_BIG_DECIMAL.test(field.getType())) {
                    events.add(SimpleConditionEvent.violated(field, field.getFullName() + " has type "
                        + field.getType().getName() + " which hides BigDecimal from @DecimalString"));
                }
            }
        };
    }

    /**
     * 規則 2 的方法條件。
     *
     * @return 方法回傳型別不得把 {@code BigDecimal} 藏在型別參數或陣列裡
     */
    private static ArchCondition<JavaMethod> notReturnTypeHidingBigDecimal() {
        return new ArchCondition<>("not return a type that involves BigDecimal without being BigDecimal "
            + "(e.g. List / Map / Optional type arguments or BigDecimal[])") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (HIDES_BIG_DECIMAL.test(method.getReturnType())) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " returns "
                        + method.getReturnType().getName() + " which hides BigDecimal from @DecimalString"));
                }
            }
        };
    }

    @Test
    @DisplayName("守門自我驗證：*Response 未標註的 BigDecimal 欄位會被抓到")
    void selfCheckUnannotatedResponseFieldIsViolation() {
        assertThat(violates(apiTransferBigDecimalFieldsAreDecimalStrings, UnannotatedAmountResponse.class)).isTrue();
    }

    @Test
    @DisplayName("守門自我驗證：*View 未標註的 BigDecimal 欄位會被抓到")
    void selfCheckUnannotatedViewFieldIsViolation() {
        assertThat(violates(apiTransferBigDecimalFieldsAreDecimalStrings, UnannotatedAmountView.class)).isTrue();
    }

    @Test
    @DisplayName("守門自我驗證：沒有字尾的 record 未標註的 BigDecimal 欄位會被抓到")
    void selfCheckUnannotatedSuffixlessRecordFieldIsViolation() {
        assertThat(violates(apiTransferBigDecimalFieldsAreDecimalStrings, UnannotatedPoint.class)).isTrue();
    }

    @Test
    @DisplayName("守門自我驗證：合規 Response 無違規；非 record 且無字尾的類別（service）不在範圍內")
    void selfCheckCompliantResponseAndOutOfScopeServiceHaveNoViolation() {
        assertThat(violates(apiTransferBigDecimalFieldsAreDecimalStrings, CompliantResponse.class)).isFalse();
        assertThat(violates(apiTransferBigDecimalFieldsAreDecimalStrings, CompliantResponse.class, PricingHelper.class))
            .isFalse();
    }

    @Test
    @DisplayName("守門自我驗證：List / Map / Optional 型別參數與 BigDecimal[] 會被規則 2 抓到")
    void selfCheckParameterizedAndArrayBigDecimalAreViolations() {
        ArchRule rule = apiTransferTypesDoNotHideBigDecimalInParameterizedOrArrayTypes;
        assertThat(violates(rule, ListAmountDto.class)).isTrue();
        assertThat(violates(rule, MapAmountDto.class)).isTrue();
        assertThat(violates(rule, OptionalAmountDto.class)).isTrue();
        assertThat(violates(rule, ArrayAmountDto.class)).isTrue();
    }

    @Test
    @DisplayName("守門自我驗證：合規 Response（raw BigDecimal 已標註、List<String>、int）不違反規則 2")
    void selfCheckCompliantResponseDoesNotViolateParameterizedRule() {
        assertThat(violates(apiTransferTypesDoNotHideBigDecimalInParameterizedOrArrayTypes, CompliantResponse.class))
            .isFalse();
    }

    @Test
    @DisplayName("守門自我驗證：未標註的 BigDecimal getter 會被規則 3 抓到")
    void selfCheckUnannotatedBigDecimalGetterIsViolation() {
        assertThat(violates(apiTransferBigDecimalAccessorsAreDecimalStrings, GetterResponse.class)).isTrue();
    }

    @Test
    @DisplayName("守門自我驗證：@JsonIgnore 的 getter 與由 component 傳播標註的 record accessor 不違反規則 3")
    void selfCheckIgnoredGetterAndAnnotatedRecordAccessorAreNotViolations() {
        assertThat(violates(apiTransferBigDecimalAccessorsAreDecimalStrings, CompliantResponse.class)).isFalse();
        // IgnoredGetterResponse 單獨匯入時規則 3 選取為空（allowEmptyShould(false) 會直接失敗），
        // 所以與合規 record 一起匯入：@JsonIgnore 的 getter 被排除，整體無違規。
        assertThat(violates(
            apiTransferBigDecimalAccessorsAreDecimalStrings, CompliantResponse.class, IgnoredGetterResponse.class
        )).isFalse();
    }

    @Test
    @DisplayName("守門自我驗證：規則 1 只看 raw BigDecimal 欄位，參數化型別與 getter 必須靠規則 2 / 3")
    void selfCheckFieldRuleAloneMissesParameterizedTypesAndGetters() {
        // 規則 1 對 List<BigDecimal> 與純 getter 選取為空（會丟 AssertionError），證明規則 2 / 3 不是重複守門。
        assertThatThrownBy(
            () -> violates(apiTransferBigDecimalFieldsAreDecimalStrings, ListAmountDto.class, GetterResponse.class)
        ).isInstanceOf(AssertionError.class).hasMessageContaining("failed to check any classes");
    }

    /**
     * 以與正式 {@code @ArchTest} 相同的規則物件評估指定的 fixture 類別。
     *
     * @param rule    要評估的規則（與正式守門同一個物件）
     * @param classes 要匯入的 fixture 類別
     * @return 有違規時為 {@code true}
     */
    private static boolean violates(ArchRule rule, Class<?>... classes) {
        return rule.evaluate(new ClassFileImporter().importClasses(classes)).hasViolation();
    }

    /** 不帶任何全域設定的 mapper：證明字串化跟著 DTO 的 annotation 走（D-05 / D-06）。 */
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("PortfolioSummaryDto：scale 8 的零金額 → \"0.00000000\"；holdingCount 等計數仍是 JSON number")
    void portfolioSummaryZeroAmountsArePlainStringsAndCountsStayNumbers() {
        BigDecimal zero = BigDecimal.ZERO.setScale(8);
        PortfolioSummaryDto summary = new PortfolioSummaryDto(
            zero, zero, zero, zero, zero, zero,
            2,
            "TWD",
            0,
            List.of(),
            zero, zero,
            1,
            zero, zero,
            List.of(),
            List.of(),
            List.of(),
            zero, zero
        );

        JsonNode node = mapper.readTree(mapper.writeValueAsString(summary));

        assertThat(node.get("cash").getNodeType()).isEqualTo(JsonNodeType.STRING);
        assertThat(node.get("cash").asString()).isEqualTo("0.00000000");
        assertThat(node.get("totalEquity").asString()).isEqualTo("0.00000000");
        assertThat(node.get("dividendIncome").asString()).isEqualTo("0.00000000");
        assertThat(node.get("holdingCount").getNodeType()).isEqualTo(JsonNodeType.NUMBER);
        assertThat(node.get("holdingCount").asInt()).isEqualTo(2);
        assertThat(node.get("dayPnlExcludedCount").getNodeType()).isEqualTo(JsonNodeType.NUMBER);
    }

    @Test
    @DisplayName("HoldingDto：roi 與數量 / 金額欄位為字串（比例也依 Java 型別字串化，D-06）")
    void holdingRatiosAndAmountsAreStrings() {
        HoldingDto holding = new HoldingDto(
            "00000000-0000-0000-0000-000000000001", "2330", "台積電",
            new BigDecimal("10.00000000"), new BigDecimal("500.00000000"), new BigDecimal("5000.00000000"),
            new BigDecimal("550.00000000"), new BigDecimal("5500.00000000"), BigDecimal.ZERO.setScale(8),
            new BigDecimal("500.00000000"), new BigDecimal("0.10000000"),
            null, null,
            "TWD", "STOCK", null, "TW", "TWD",
            new BigDecimal("5500.00000000"), new BigDecimal("500.00000000"), null,
            null, null, null
        );

        JsonNode node = mapper.readTree(mapper.writeValueAsString(holding));

        assertThat(node.get("roi").getNodeType()).isEqualTo(JsonNodeType.STRING);
        assertThat(node.get("roi").asString()).isEqualTo("0.10000000");
        assertThat(node.get("totalQuantity").asString()).isEqualTo("10.00000000");
        assertThat(node.get("realizedPnl").asString()).isEqualTo("0.00000000");
        assertThat(node.get("previousClose").isNull()).isTrue();
    }

    @Test
    @DisplayName("PortfolioActivityDto：金額為字串，ratioNew / ratioOld（Integer）仍是 JSON number")
    void activityAmountsAreStringsAndRatiosStayNumbers() {
        PortfolioActivityDto split = new PortfolioActivityDto(
            "00000000-0000-0000-0000-000000000002", "SHARE_ADJUSTMENT", "2330", "TWD",
            null, null, null,
            new BigDecimal("10.00000000"), null, null, null, null,
            2, 1,
            null, null, null, null,
            false, true
        );

        JsonNode node = mapper.readTree(mapper.writeValueAsString(split));

        assertThat(node.get("quantity").getNodeType()).isEqualTo(JsonNodeType.STRING);
        assertThat(node.get("quantity").asString()).isEqualTo("10.00000000");
        assertThat(node.get("ratioNew").getNodeType()).isEqualTo(JsonNodeType.NUMBER);
        assertThat(node.get("ratioNew").asInt()).isEqualTo(2);
        assertThat(node.get("ratioOld").getNodeType()).isEqualTo(JsonNodeType.NUMBER);
    }

    @Test
    @DisplayName("CreateTradeRequest：字串金額反序列化逐字精確，fee 為 null 維持 null")
    void createTradeRequestDeserializesStringAmountsExactly() {
        String json = "{\"symbol\":\"2330\",\"type\":\"BUY\",\"quantity\":\"3\",\"price\":\"123.45\","
            + "\"fee\":null,\"note\":null,\"executedAt\":null}";

        CreateTradeRequest request = mapper.readValue(json, CreateTradeRequest.class);

        assertThat(request.quantity()).isEqualTo(new BigDecimal("3"));
        assertThat(request.price()).isEqualTo(new BigDecimal("123.45"));
        assertThat(request.price().scale()).isEqualTo(2);
        assertThat(request.fee()).isNull();
    }
}
