package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TradeSortKey} 與 {@link SortDirection} 白名單解析的單元測試（D-06 / D-07）。
 *
 * <p>ORDER BY 無法以具名參數繫結，故排序鍵與方向只能以 enum 白名單映射到寫死的 SQL 片段。
 * 本測試同時鎖定「預設值」與「非法值一律轉 VALIDATION_FAILED」兩項行為，
 * 並以注入字串 negative case 證明使用者輸入永不進入 SQL。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("排序白名單解析")
class TradeSortKeyTest {

    @Test
    @DisplayName("sort 為 null 或空白時回傳預設排序鍵 EXECUTED_AT")
    void blankSortKeyFallsBackToExecutedAt() {
        assertThat(TradeSortKey.fromApiValue(null)).isEqualTo(TradeSortKey.EXECUTED_AT);
        assertThat(TradeSortKey.fromApiValue("")).isEqualTo(TradeSortKey.EXECUTED_AT);
        assertThat(TradeSortKey.fromApiValue("   ")).isEqualTo(TradeSortKey.EXECUTED_AT);
    }

    @Test
    @DisplayName("白名單內的 sort 值解析為對應排序鍵（大小寫不敏感）")
    void whitelistedSortKeysAreParsed() {
        assertThat(TradeSortKey.fromApiValue("executedAt")).isEqualTo(TradeSortKey.EXECUTED_AT);
        assertThat(TradeSortKey.fromApiValue("EXECUTEDAT")).isEqualTo(TradeSortKey.EXECUTED_AT);
        assertThat(TradeSortKey.fromApiValue(" total ")).isEqualTo(TradeSortKey.TOTAL);
        assertThat(TradeSortKey.fromApiValue("QUANTITY")).isEqualTo(TradeSortKey.QUANTITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "created_at", "t.id", "executed_at; drop table transactions", "1=1"})
    @DisplayName("白名單外的 sort 值一律丟 VALIDATION_FAILED，含 SQL 注入字串")
    void unknownSortKeyIsRejected(String value) {
        assertThatThrownBy(() -> TradeSortKey.fromApiValue(value))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("排序鍵的 SQL 片段是寫死常數，與使用者輸入無關")
    void sortKeysExposeHardcodedSqlFragments() {
        assertThat(TradeSortKey.EXECUTED_AT.sqlFragment()).isEqualTo("t.executed_at");
        assertThat(TradeSortKey.TOTAL.sqlFragment()).isEqualTo("(t.quantity * t.price)");
        assertThat(TradeSortKey.QUANTITY.sqlFragment()).isEqualTo("t.quantity");
    }

    @Test
    @DisplayName("非法 sort 值的錯誤訊息不回射原始輸入（避免反射式輸出）")
    void rejectionMessageDoesNotEchoInput() {
        String injection = "executed_at; drop table transactions";

        assertThatThrownBy(() -> TradeSortKey.fromApiValue(injection))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("drop table");
    }

    @Test
    @DisplayName("direction 為 null 或空白時回傳預設方向 DESC")
    void blankDirectionFallsBackToDesc() {
        assertThat(SortDirection.fromApiValue(null)).isEqualTo(SortDirection.DESC);
        assertThat(SortDirection.fromApiValue("")).isEqualTo(SortDirection.DESC);
        assertThat(SortDirection.fromApiValue("  ")).isEqualTo(SortDirection.DESC);
    }

    @Test
    @DisplayName("direction 大小寫不敏感解析為 ASC / DESC")
    void directionIsCaseInsensitive() {
        assertThat(SortDirection.fromApiValue("asc")).isEqualTo(SortDirection.ASC);
        assertThat(SortDirection.fromApiValue("ASC")).isEqualTo(SortDirection.ASC);
        assertThat(SortDirection.fromApiValue("Desc")).isEqualTo(SortDirection.DESC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"up", "down", "asc, (select 1)", "ascending"})
    @DisplayName("白名單外的 direction 值一律丟 VALIDATION_FAILED")
    void unknownDirectionIsRejected(String value) {
        assertThatThrownBy(() -> SortDirection.fromApiValue(value))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("排序方向的 SQL 關鍵字是寫死常數")
    void directionsExposeHardcodedSqlKeywords() {
        assertThat(SortDirection.ASC.sqlKeyword()).isEqualTo("asc");
        assertThat(SortDirection.DESC.sqlKeyword()).isEqualTo("desc");
    }
}
