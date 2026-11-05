package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.ActivitySortKey;
import dowob.xyz.stockwebv2.trading.domain.ActivityTypeFilter;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityQuery;
import dowob.xyz.stockwebv2.trading.repository.JdbcActivityRepository.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcActivityRepository} 的 SQL 組成測試（不連資料庫，PORT-13，T-04.2-22 / T-04.2-23）。
 *
 * <p>鎖定：種類篩選只決定拼接哪些<strong>寫死</strong>的分支（買入 / 賣出另以參數繫結交易類型）；
 * 每個被拼接的分支都自帶擁有者條件；標的與時間區間以參數繫結、作用於事件時間（半開區間）；
 * 排序為所選欄位 {@code nulls last} 加 {@code created_at}、{@code id} tie-breaker；count 與 list 套用同一組篩選。
 * 實際的資料庫行為（排序、分頁、沖銷旗標）由 {@code PortfolioActivityApiIT} 驗收。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("活動列表 SQL 組成")
class JdbcActivityRepositorySqlTest {

    private static final long USER_ID = 42L;
    private static final String TRADES = "from transactions t";
    private static final String DIVIDENDS = "from cash_dividends d";
    private static final String ADJUSTMENTS = "from share_adjustments s";

    @Test
    @DisplayName("不篩種類 → 三個分支 union all，各自帶 user_id 條件")
    void allKindsUnionThreeBranchesEachScopedToOwner() {
        Statement list = JdbcActivityRepository.listStatement(USER_ID, query(Optional.empty()));

        assertThat(list.sql()).contains(TRADES, DIVIDENDS, ADJUSTMENTS);
        assertThat(list.sql().split("(?i)union all", -1)).hasSize(3);
        assertThat(list.sql()).contains("where t.user_id = :userId", "where d.user_id = :userId",
            "where s.user_id = :userId");
        assertThat(list.sql()).doesNotContain(":tradeType");
        assertThat(list.params()).containsEntry("userId", USER_ID).doesNotContainKey("tradeType");
    }

    @Test
    @DisplayName("交易列 amount = quantity × price 捨入至 scale 8（與其他金額欄位一致，不輸出 scale 16）")
    void tradeAmountIsRoundedToMoneyScale() {
        Statement list = JdbcActivityRepository.listStatement(USER_ID, query(Optional.of(ActivityTypeFilter.BUY)));

        assertThat(list.sql()).contains("round(t.quantity * t.price, 8) as amount");
        assertThat(list.sql()).doesNotContain("t.quantity * t.price as amount");
    }

    @Test
    @DisplayName("type=DIVIDEND → 只拼接 cash_dividends 分支")
    void dividendFilterUsesOnlyDividendBranch() {
        Statement list = JdbcActivityRepository.listStatement(USER_ID, query(Optional.of(ActivityTypeFilter.DIVIDEND)));

        assertThat(list.sql()).contains(DIVIDENDS, "where d.user_id = :userId");
        assertThat(list.sql()).doesNotContain(TRADES, ADJUSTMENTS).doesNotContainIgnoringCase("union all");
    }

    @Test
    @DisplayName("type=SHARE_ADJUSTMENT → 只拼接 share_adjustments 分支")
    void shareAdjustmentFilterUsesOnlyAdjustmentBranch() {
        Statement list = JdbcActivityRepository.listStatement(
            USER_ID, query(Optional.of(ActivityTypeFilter.SHARE_ADJUSTMENT)));

        assertThat(list.sql()).contains(ADJUSTMENTS, "where s.user_id = :userId");
        assertThat(list.sql()).doesNotContain(TRADES, DIVIDENDS).doesNotContainIgnoringCase("union all");
    }

    @Test
    @DisplayName("type=BUY / SELL → 只拼接 transactions 分支並加 t.type = :tradeType（值以參數繫結）")
    void tradeFiltersUseOnlyTradeBranchWithBoundType() {
        Statement buy = JdbcActivityRepository.listStatement(USER_ID, query(Optional.of(ActivityTypeFilter.BUY)));
        Statement sell = JdbcActivityRepository.countStatement(USER_ID, query(Optional.of(ActivityTypeFilter.SELL)));

        assertThat(buy.sql()).contains(TRADES, "t.type = :tradeType");
        assertThat(buy.sql()).doesNotContain(DIVIDENDS, ADJUSTMENTS, "'BUY'");
        assertThat(buy.params()).containsEntry("tradeType", "BUY");
        assertThat(sell.sql()).contains(TRADES, "t.type = :tradeType").doesNotContain(DIVIDENDS, ADJUSTMENTS);
        assertThat(sell.params()).containsEntry("tradeType", "SELL");
    }

    @Test
    @DisplayName("標的與時間區間 → 外層以參數繫結（事件時間半開區間）；list 與 count 同一組篩選")
    void assetAndDateRangeAreBoundOnOccurredAt() {
        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        ActivityQuery query = new ActivityQuery(
            Optional.empty(), 7L, from, to, ActivitySortKey.EXECUTED_AT, SortDirection.DESC, 0, 20);

        Statement list = JdbcActivityRepository.listStatement(USER_ID, query);
        Statement count = JdbcActivityRepository.countStatement(USER_ID, query);

        for (Statement statement : new Statement[] {list, count}) {
            assertThat(statement.sql()).contains(
                "a.asset_id = :assetId", "a.occurred_at >= :dateFrom", "a.occurred_at < :dateTo");
            assertThat(statement.params())
                .containsEntry("assetId", 7L)
                .containsEntry("dateFrom", from)
                .containsEntry("dateTo", to);
        }
        assertThat(count.sql()).doesNotContainIgnoringCase("order by").doesNotContain(":limit");
    }

    @Test
    @DisplayName("沒有標的與時間條件 → 不出現對應條件與參數")
    void absentFiltersAddNoConditions() {
        Statement list = JdbcActivityRepository.listStatement(USER_ID, query(Optional.empty()));

        assertThat(list.sql()).doesNotContain(":assetId", ":dateFrom", ":dateTo");
        assertThat(list.params()).doesNotContainKeys("assetId", "dateFrom", "dateTo");
    }

    @Test
    @DisplayName("sort=quantity asc → order by a.quantity asc nulls last, a.created_at asc, a.id asc；分頁以參數繫結")
    void orderByUsesNullsLastAndTieBreakers() {
        ActivityQuery query = new ActivityQuery(
            Optional.empty(), null, null, null, ActivitySortKey.QUANTITY, SortDirection.ASC, 3, 5);

        Statement list = JdbcActivityRepository.listStatement(USER_ID, query);

        assertThat(list.sql()).contains("order by a.quantity asc nulls last, a.created_at asc, a.id asc");
        assertThat(list.params()).containsEntry("limit", 5).containsEntry("offset", 15L);
    }

    private static ActivityQuery query(Optional<ActivityTypeFilter> type) {
        return new ActivityQuery(type, null, null, null, ActivitySortKey.EXECUTED_AT, SortDirection.DESC, 0, 20);
    }
}
