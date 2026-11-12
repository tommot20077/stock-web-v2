package dowob.xyz.stockwebv2.infrastructure.web.json;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 標在 API DTO / Request 的 {@link java.math.BigDecimal} 欄位上：序列化為一般記號、保留 scale 的 JSON 字串。
 *
 * <p>這是 meta-annotation（{@link JacksonAnnotationsInside}），等同
 * {@code @JsonSerialize(using = PlainDecimalStringSerializer.class)}，理由見 {@link PlainDecimalStringSerializer}。</p>
 *
 * <p><strong>為什麼逐欄位標註、不用全域 {@code JsonMapperBuilderCustomizer}</strong>（Phase 5 D-05 / D-06，
 * RESEARCH §Q2 方案 C）：全域設定定義在 {@code stock-start}，模組的 {@code @WebMvcTest} slice 拿不到，
 * 測試與正式環境會分歧；而且會連帶改動 Redis 快取等非 API 的 JSON。annotation 跟著 DTO 走，slice、快取、正式 HTTP 一致。</p>
 *
 * <p><strong>請求端</strong>：標在 Request 上只影響序列化；反序列化沿用 Jackson 預設（JSON 字串 → {@code BigDecimal}），
 * 標註是為了全 API 一致並讓 D-09 的守門規則能一律檢查。</p>
 *
 * <p><strong>限制</strong>：{@code @Target} 不含 {@code TYPE_USE}，不能寫成 {@code List<@DecimalString BigDecimal>}；
 * 目前 API 沒有這種欄位。record component 上的標註會依 {@code FIELD} / {@code METHOD} target 傳播到欄位與 accessor。</p>
 *
 * <p><strong>只用於 HTTP DTO</strong>：Kafka 事件（如 {@code PriceTickEvent}）走 Jackson 2，不適用本 annotation。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = PlainDecimalStringSerializer.class)
public @interface DecimalString {
}
