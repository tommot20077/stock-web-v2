package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.infrastructure.web.json.DecimalString;
import dowob.xyz.stockwebv2.trading.api.CreateTradeRequest;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioActivityDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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
