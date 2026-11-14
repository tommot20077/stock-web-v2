package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;
import dowob.xyz.stockwebv2.start.archfixture.api.ApiDecimalStringRuleFixtures.CompliantResponse;
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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.math.BigDecimal;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static org.assertj.core.api.Assertions.assertThat;

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
