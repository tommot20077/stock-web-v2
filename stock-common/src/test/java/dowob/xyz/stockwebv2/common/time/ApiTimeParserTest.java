package dowob.xyz.stockwebv2.common.time;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser.RangeBound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApiTimeParser} 的解析規則單元測試。
 *
 * <p>其中「空白還原成 {@code '+'}」一項只能在此層驗證：MockMvc 的 {@code .param(...)} 與
 * 查詢字串解析都不會套用 servlet 的 {@code x-www-form-urlencoded} 解碼，
 * 也就無法重現 {@code '+'} 被吃掉的現象。本測試改為直接餵入「已被解碼破壞」的字串，
 * 精確鎖定修補行為本身。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("API 時間參數解析")
class ApiTimeParserTest {

    @Test
    @DisplayName("帶 Z 或偏移量的時間戳直接解析為該瞬間")
    void offsetTimestampIsParsedAsIs() {
        assertThat(parseFrom("2026-01-01T00:00:00Z"))
            .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(parseFrom("2026-01-01T00:00:00+08:00"))
            .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+08:00"));
    }

    @Test
    @DisplayName("servlet 解碼把 '+' 變成空白後仍能解析出原本的偏移量")
    void plusSignDamagedByServletDecodingIsRestored() {
        // Tomcat 依 x-www-form-urlencoded 規則解碼 query string，未百分比編碼的 '+' 會變成空白，
        // 服務層實際收到的就是下面這個字串。空白在中間，trim() 救不了。
        assertThat(parseFrom("2026-01-01T00:00:00 08:00"))
            .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+08:00"));
    }

    @Test
    @DisplayName("未帶偏移量的時間戳補上 DEFAULT_OFFSET")
    void offsetLessTimestampFallsBackToDefaultOffset() {
        assertThat(parseFrom("2026-01-01T09:30:00"))
            .isEqualTo(OffsetDateTime.parse("2026-01-01T09:30:00Z"));
    }

    @Test
    @DisplayName("DEFAULT_OFFSET 固定為 UTC:同一組參數在任何部署環境都得到相同結果")
    void defaultOffsetIsUtc() {
        assertThat(ApiTimeParser.DEFAULT_OFFSET).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("純日期作為下界取當日 00:00")
    void dateOnlyLowerBoundIsStartOfDay() {
        assertThat(parseFrom("2026-01-31"))
            .isEqualTo(OffsetDateTime.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    @DisplayName("純日期作為上界取隔日 00:00，使半開區間完整涵蓋當天")
    void dateOnlyUpperBoundIsStartOfNextDay() {
        assertThat(parseTo("2026-01-31"))
            .isEqualTo(OffsetDateTime.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    @DisplayName("null 或空白代表未指定，回傳 null")
    void blankValueMeansUnspecified() {
        assertThat(parseFrom(null)).isNull();
        assertThat(parseFrom("   ")).isNull();
    }

    @Test
    @DisplayName("三種格式皆不符時丟 VALIDATION_FAILED，訊息不回射原始輸入")
    void unparseableValueIsRejectedWithoutEchoingInput() {
        assertThatThrownBy(() -> parseFrom("not-a-date"))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("not-a-date")
            .hasMessageContaining("dateFrom")
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> parseTo("2026-13-45"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private OffsetDateTime parseFrom(String value) {
        return ApiTimeParser.parseRangeBound(value, "dateFrom", RangeBound.LOWER);
    }

    private OffsetDateTime parseTo(String value) {
        return ApiTimeParser.parseRangeBound(value, "dateTo", RangeBound.UPPER);
    }
}
