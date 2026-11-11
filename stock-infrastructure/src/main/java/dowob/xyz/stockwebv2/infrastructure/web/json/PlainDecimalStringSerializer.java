package dowob.xyz.stockwebv2.infrastructure.web.json;

import java.math.BigDecimal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * 把 {@link BigDecimal} 以<strong>一般記號（plain notation）的 JSON 字串</strong>輸出的 Jackson 3 serializer。
 *
 * <p><strong>為什麼是字串</strong>：JSON number 在瀏覽器端會被解析成 double，超過約 15–17 位有效數字就被無聲改寫
 * （Phase 5 D-05：所有 API DTO / Request 的 {@code BigDecimal} 欄位一律字串，判斷依據是 Java 型別，D-06）。</p>
 *
 * <p><strong>為什麼是 {@link BigDecimal#toPlainString()} 而不是 {@code toString()}</strong>：
 * {@code BigDecimal.ZERO.setScale(8).toString()} 是 {@code "0E-8"}、{@code 0.00000001} 是 {@code "1E-8"}；
 * 後端確實會產生這種值（{@code PortfolioQueryService} 的現金 / 股利預設值 {@code ZERO.setScale(MONEY_SCALE)}）。
 * 科學記號字串會讓前端任何字串邏輯（regex、{@code includes('.')}、CSV 原樣輸出）出錯。
 * {@code toPlainString()} 保留 scale（{@code 100.00000000 → "100.00000000"}），也展開負 scale（{@code 1E+3 → "1000"}）。</p>
 *
 * <p>null 不會進到這裡：Jackson 對 null 值使用 null serializer，輸出 JSON {@code null}。</p>
 *
 * <p><strong>請求端</strong>不需要對應的 deserializer：Jackson 預設就會把 JSON 字串精確轉成 {@code BigDecimal}，
 * {@code @DecimalMin} / {@code @Digits} 照常生效（D-07；依 CONTEXT「Claude's Discretion」也不拒收 JSON number）。</p>
 *
 * <p>一般不直接引用本類別，而是在欄位上標 {@link DecimalString}（D-09 的守門測試也以該 annotation 為準）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class PlainDecimalStringSerializer extends StdSerializer<BigDecimal> {

    /**
     * 無參建構子，供 {@code @JsonSerialize(using = ...)} 以反射建立實例。
     */
    public PlainDecimalStringSerializer() {
        super(BigDecimal.class);
    }

    /**
     * 以 {@link BigDecimal#toPlainString()} 寫出 JSON 字串。
     *
     * @param value 要序列化的金額 / 比率，非 null
     * @param gen   JSON 產生器
     * @param ctxt  序列化 context（未使用）
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(value.toPlainString());
    }
}
