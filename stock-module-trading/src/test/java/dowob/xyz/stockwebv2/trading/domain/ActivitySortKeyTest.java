package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivitySortKey} 與 {@link ActivityTypeFilter} 白名單解析的單元測試（PORT-13，T-04.2-23）。
 *
 * <p>排序片段與分支選擇都只能由 enum 挑選寫死的 SQL；本測試鎖定白名單、預設值、片段字面，
 * 以及白名單外（含注入字串）一律 400 {@code fields.sort} / {@code fields.type} 且不回射原始輸入。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("活動列表排序鍵與種類篩選白名單")
class ActivitySortKeyTest {

    @Test
    @DisplayName("sort 白名單：executedAt / createdAt / total / quantity 對應外層別名 a 的寫死片段（大小寫不敏感）")
    void whitelistedSortKeysMapToHardcodedFragments() {
        assertThat(ActivitySortKey.fromApiValue("executedAt")).isEqualTo(ActivitySortKey.EXECUTED_AT);
        assertThat(ActivitySortKey.fromApiValue("createdAt")).isEqualTo(ActivitySortKey.CREATED_AT);
        assertThat(ActivitySortKey.fromApiValue(" TOTAL ")).isEqualTo(ActivitySortKey.TOTAL);
        assertThat(ActivitySortKey.fromApiValue("quantity")).isEqualTo(ActivitySortKey.QUANTITY);

        assertThat(ActivitySortKey.EXECUTED_AT.sqlFragment()).isEqualTo("a.occurred_at");
        assertThat(ActivitySortKey.CREATED_AT.sqlFragment()).isEqualTo("a.created_at");
        assertThat(ActivitySortKey.TOTAL.sqlFragment()).isEqualTo("a.amount");
        assertThat(ActivitySortKey.QUANTITY.sqlFragment()).isEqualTo("a.quantity");
    }

    @Test
    @DisplayName("sort 為 null 或空白 → 預設 EXECUTED_AT")
    void blankSortFallsBackToExecutedAt() {
        assertThat(ActivitySortKey.fromApiValue(null)).isEqualTo(ActivitySortKey.EXECUTED_AT);
        assertThat(ActivitySortKey.fromApiValue("  ")).isEqualTo(ActivitySortKey.EXECUTED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "occurred_at", "a.id", "executedAt; drop table transactions", "1=1"})
    @DisplayName("白名單外的 sort（含注入字串）→ VALIDATION_FAILED fields.sort，訊息不回射原始輸入")
    void unknownSortIsRejectedWithFieldError(String value) {
        assertThatThrownBy(() -> ActivitySortKey.fromApiValue(value))
            .isInstanceOf(FieldValidationException.class)
            .hasMessageNotContaining("drop table")
            .satisfies(error -> {
                BusinessException business = (BusinessException) error;
                assertThat(business.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(business.fields()).containsOnlyKeys("sort");
                assertThat(business.fields().get("sort")).doesNotContain(value);
            });
    }

    @Test
    @DisplayName("type 白名單：BUY / SELL / DIVIDEND / SHARE_ADJUSTMENT（大小寫不敏感）")
    void whitelistedTypesAreParsed() {
        assertThat(ActivityTypeFilter.fromApiValue("BUY")).contains(ActivityTypeFilter.BUY);
        assertThat(ActivityTypeFilter.fromApiValue("sell")).contains(ActivityTypeFilter.SELL);
        assertThat(ActivityTypeFilter.fromApiValue(" Dividend ")).contains(ActivityTypeFilter.DIVIDEND);
        assertThat(ActivityTypeFilter.fromApiValue("SHARE_ADJUSTMENT")).contains(ActivityTypeFilter.SHARE_ADJUSTMENT);
    }

    @Test
    @DisplayName("type 為 null 或空白 → empty（三種事件全部）")
    void blankTypeMeansAll() {
        assertThat(ActivityTypeFilter.fromApiValue(null)).isEqualTo(Optional.empty());
        assertThat(ActivityTypeFilter.fromApiValue(" ")).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOO", "CASH_DIVIDEND", "SPLIT", "STOCK_DIVIDEND", "BUY' or '1'='1"})
    @DisplayName("白名單外的 type（含 kind 名稱與注入字串）→ VALIDATION_FAILED fields.type")
    void unknownTypeIsRejectedWithFieldError(String value) {
        assertThatThrownBy(() -> ActivityTypeFilter.fromApiValue(value))
            .isInstanceOf(FieldValidationException.class)
            .satisfies(error -> {
                BusinessException business = (BusinessException) error;
                assertThat(business.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(business.fields()).containsOnlyKeys("type");
                assertThat(business.getMessage()).doesNotContain(value);
            });
    }
}
