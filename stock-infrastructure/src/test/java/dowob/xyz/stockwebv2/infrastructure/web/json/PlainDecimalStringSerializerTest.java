package dowob.xyz.stockwebv2.infrastructure.web.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PlainDecimalStringSerializer} 與 {@link DecimalString} 的單元測試。
 *
 * <p>刻意用 {@code JsonMapper.builder().build()} 而不是 Spring Boot 的 mapper：{@code @DecimalString}
 * 跟著 DTO 走、不依賴任何全域設定，模組的 {@code @WebMvcTest} slice 與正式環境才會一致（Phase 5 D-05 / D-06）。</p>
 *
 * <p>重點反例是 {@code BigDecimal.ZERO.setScale(8)}：{@code toString()} 會輸出 {@code 0E-8}，
 * {@code PortfolioQueryService} 的現金 / 股利預設值正是這種值。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("BigDecimal 以一般記號字串序列化（@DecimalString）")
class PlainDecimalStringSerializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 測試用 record：兩個標註欄位（一個會是 null）加一個不受影響的 int 欄位。
     *
     * @param amount   標註的金額
     * @param nullable 標註但為 null 的金額
     * @param count    未標註的整數，應維持 JSON number
     */
    record Sample(@DecimalString BigDecimal amount, @DecimalString BigDecimal nullable, int count) {
    }

    /**
     * 只含一個標註金額的請求樣本，用來驗證反序列化（字串 → BigDecimal）。
     *
     * @param amount 金額
     */
    record AmountOnly(@DecimalString BigDecimal amount) {
    }

    @Test
    @DisplayName("scale 8 的零 → \"0.00000000\"（不是 0E-8）；null 維持 null；int 仍是 number")
    void zeroWithScaleIsPlainStringAndNullStaysNull() {
        String json = mapper.writeValueAsString(new Sample(BigDecimal.ZERO.setScale(8), null, 3));

        assertThat(json).isEqualTo("{\"amount\":\"0.00000000\",\"nullable\":null,\"count\":3}");
    }

    @Test
    @DisplayName("0.00000001 → \"0.00000001\"（不是 1E-8）")
    void smallestUnitIsPlainString() {
        assertThat(serializeAmount(new BigDecimal("0.00000001"))).isEqualTo("\"0.00000001\"");
    }

    @Test
    @DisplayName("超過 double 有效位數的金額逐字輸出")
    void largePreciseAmountIsVerbatim() {
        assertThat(serializeAmount(new BigDecimal("123456789012.12345678")))
            .isEqualTo("\"123456789012.12345678\"");
    }

    @Test
    @DisplayName("保留 scale：100.00000000 → \"100.00000000\"")
    void scaleIsPreserved() {
        assertThat(serializeAmount(new BigDecimal("100.00000000"))).isEqualTo("\"100.00000000\"");
    }

    @Test
    @DisplayName("負 scale：1E+3 → \"1000\"")
    void negativeScaleIsExpanded() {
        assertThat(serializeAmount(new BigDecimal("1E+3"))).isEqualTo("\"1000\"");
    }

    @Test
    @DisplayName("反序列化：JSON 字串金額轉回 BigDecimal，值與 scale 皆不變")
    void stringAmountDeserializesExactly() {
        AmountOnly parsed = mapper.readValue("{\"amount\":\"123456789012.12345678\"}", AmountOnly.class);

        assertThat(parsed.amount()).isEqualByComparingTo("123456789012.12345678");
        assertThat(parsed.amount().toPlainString()).isEqualTo("123456789012.12345678");
    }

    private String serializeAmount(BigDecimal amount) {
        String json = mapper.writeValueAsString(new AmountOnly(amount));
        String prefix = "{\"amount\":";
        assertThat(json).startsWith(prefix).endsWith("}");
        return json.substring(prefix.length(), json.length() - 1);
    }
}
