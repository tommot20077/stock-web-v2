package dowob.xyz.stockwebv2.common.time;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * HTTP query string 中 ISO-8601 時間參數的共用解析器。
 *
 * <p>各模組原本各自處理時間參數（market-data 用 {@code Instant.parse} 與型別化繫結、
 * trading 用自己的一份 {@code OffsetDateTime.parse}），對「可接受哪些格式」與「格式錯誤時
 * 回什麼訊息」沒有一致答案。本類把規則集中於一處，新端點只要呼叫即可得到相同語意。</p>
 *
 * <h2>為什麼一定要修補 {@code '+'}</h2>
 *
 * <p>Servlet 容器對 query string 採用 {@code application/x-www-form-urlencoded} 的解碼規則，
 * 會把未經百分比編碼的 {@code '+'} 解成空白。因此客戶端送出的
 * {@code 2026-01-01T00:00:00+08:00} 在 {@code getParameter} 取得時已變成
 * {@code 2026-01-01T00:00:00 08:00}，任何嚴格的 ISO-8601 解析都會失敗。
 * 這發生在 Spring 的型別轉換之前，所以改用型別化的 {@code @RequestParam OffsetDateTime}
 * <strong>不會</strong>讓問題消失。合法的 ISO-8601 值本身不含空白，
 * 因此把空白一律還原成 {@code '+'} 是安全的修補。</p>
 *
 * <p>錯誤訊息一律只描述期望格式、不回射原始輸入值，避免使用者可控字串被反射回應答
 * （code-standards 錯誤訊息安全規則）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ApiTimeParser {

    private ApiTimeParser() {
    }

    /**
     * 區間端點的角色，決定純日期形式要取當日起點或隔日起點。
     */
    public enum RangeBound {
        /**
         * 區間下界（含）。
         */
        LOWER,

        /**
         * 區間上界（不含）。
         */
        UPPER
    }

    /**
     * 解析查詢區間端點，接受三種 ISO-8601 形式並一律正規化成 {@link OffsetDateTime}。
     *
     * <ul>
     *   <li>{@code 2026-01-01T00:00:00Z} / {@code 2026-01-01T00:00:00+08:00} — 帶偏移量，即為該瞬間。</li>
     *   <li>{@code 2026-01-01T00:00:00} — 未帶偏移量，補上 {@code defaultOffset}。</li>
     *   <li>{@code 2026-01-01} — 純日期，視為<strong>整個當日</strong>：作為下界取當日 00:00，
     *       作為上界取隔日 00:00。半開區間 {@code [from, to)} 因此在純日期形式下會完整涵蓋
     *       上界當天，符合日期選擇器的直覺。</li>
     * </ul>
     *
     * @param rawValue      使用者傳入的原始字串；null 或空白代表未指定
     * @param field         欄位名稱，用於組錯誤訊息
     * @param bound         此端點是區間下界或上界
     * @param defaultOffset 未帶偏移量的值所採用的基準偏移
     * @return 解析後的時間；未指定時回傳 null
     * @throws BusinessException 三種格式皆無法解析時丟出 {@link ErrorCode#VALIDATION_FAILED}
     */
    public static OffsetDateTime parseRangeBound(
        String rawValue,
        String field,
        RangeBound bound,
        ZoneOffset defaultOffset
    ) {
        if (StringUtils.isBlank(rawValue)) {
            return null;
        }
        String normalized = restorePlusSign(rawValue.trim());
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException notAnOffsetDateTime) {
            return parseWithoutOffset(normalized, field, bound, defaultOffset);
        }
    }

    /**
     * 還原被 servlet query string 解碼吃掉的 {@code '+'}。
     *
     * @param value 已 trim 的原始字串
     * @return 空白全部還原成 {@code '+'} 的字串
     */
    public static String restorePlusSign(String value) {
        return value.replace(' ', '+');
    }

    private static OffsetDateTime parseWithoutOffset(
        String normalized,
        String field,
        RangeBound bound,
        ZoneOffset defaultOffset
    ) {
        try {
            return LocalDateTime.parse(normalized).atOffset(defaultOffset);
        } catch (DateTimeParseException notALocalDateTime) {
            return parseDateOnly(normalized, field, bound, defaultOffset);
        }
    }

    private static OffsetDateTime parseDateOnly(
        String normalized,
        String field,
        RangeBound bound,
        ZoneOffset defaultOffset
    ) {
        try {
            LocalDate date = LocalDate.parse(normalized);
            LocalDate resolved = bound == RangeBound.UPPER ? date.plusDays(1) : date;
            return resolved.atStartOfDay().atOffset(defaultOffset);
        } catch (DateTimeParseException notADate) {
            throw new BusinessException(
                ErrorCode.VALIDATION_FAILED, field + " must be an ISO-8601 date or timestamp");
        }
    }
}
