package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.ActivityKind;
import dowob.xyz.stockwebv2.trading.domain.ActivityTypeFilter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 以三表 {@code union all} 實作的統一活動列表（PORT-13，D-12）。
 *
 * <p><strong>SQL 組成全部是寫死的字串常數</strong>：三個分支、排序片段（{@code ActivitySortKey}）與方向
 * （{@code SortDirection}）都來自 enum，使用者輸入只用於挑選常數；所有值一律 {@code .param} 繫結（T-04.2-23）。</p>
 *
 * <p><strong>每個分支各自帶 {@code where user_id = :userId}</strong>（T-04.2-22）：擁有者條件不放在外層，
 * 任何一個分支漏掉都會讓他人的紀錄出現在列表裡，因此不依賴優化器把外層條件推進分支。</p>
 *
 * <p>種類篩選以寫死的分支選擇實作（見 {@link #filter}），不是在 UNION 之後再篩 {@code kind}：
 * 只查需要的表，也讓「股利」頁籤不必掃交易表。</p>
 *
 * <p>本類<strong>不 JOIN 資產主檔</strong>（D-21，{@code TradingSqlBoundaryTest}）：只輸出 {@code asset_id}，
 * symbol / currency 由 service 經 {@code AssetFacade.findProfiles} 一次補上。</p>
 *
 * <p>三個分支輸出同一組欄位（契約〈各 kind 欄位適用表〉），不適用的欄位以帶型別的 null 補齊，讓 UNION 的型別
 * 解析不依賴字面值推斷：</p>
 * <ul>
 *   <li>交易：{@code amount = quantity × price}（與 {@code TradeSortKey.TOTAL} 同一運算式，不含手續費）；
 *       交易沒有沖銷機制，{@code reversed} / {@code reversible} 恆 false。</li>
 *   <li>現金股利：沖銷列以自我 left join 取原列的 UUID 與入帳時間；{@code reversible} = 非沖銷列且未被沖銷。</li>
 *   <li>股數調整：沖銷列也輸出原值（不反號，前端以 {@code reversalOfId} 判斷）；{@code reversible} 另須為該標的
 *       最晚一筆「非沖銷且未被沖銷」的調整——「最晚」依 ({@code effective_at}, {@code id}) 排序，與
 *       {@code JdbcShareAdjustmentRepository#findLatestActive} 一致（同一除權日依記錄順序，2026-11-04 裁決）。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public class JdbcActivityRepository implements ActivityRepository {

    /**
     * 交易分支（{@code transactions}）。
     */
    private static final String TRADE_BRANCH = """
        select t.uuid as id, t.type::varchar as kind, t.asset_id, t.executed_at as occurred_at,
               null::date as effective_date, t.created_at, t.quantity, t.price, t.fee,
               t.quantity * t.price as amount, null::numeric as withholding_tax,
               null::integer as ratio_new, null::integer as ratio_old, t.note,
               null::uuid as reversal_of_id, null::timestamptz as reversal_of_occurred_at,
               null::date as reversal_of_effective_date, false as reversed, false as reversible
        from transactions t
        where t.user_id = :userId
        """;

    /**
     * 現金股利分支（{@code cash_dividends}，含沖銷列）。
     */
    private static final String DIVIDEND_BRANCH = """
        select d.uuid as id, 'CASH_DIVIDEND'::varchar as kind, d.asset_id, d.paid_at as occurred_at,
               null::date as effective_date, d.created_at, null::numeric as quantity, null::numeric as price,
               null::numeric as fee, d.amount, d.withholding_tax,
               null::integer as ratio_new, null::integer as ratio_old, d.note,
               o.uuid as reversal_of_id, o.paid_at as reversal_of_occurred_at,
               null::date as reversal_of_effective_date,
               exists (select 1 from cash_dividends r where r.reversal_of_id = d.id) as reversed,
               (d.reversal_of_id is null
                and not exists (select 1 from cash_dividends r where r.reversal_of_id = d.id)) as reversible
        from cash_dividends d
        left join cash_dividends o on o.id = d.reversal_of_id and o.user_id = d.user_id
        where d.user_id = :userId
        """;

    /**
     * 股數調整分支（{@code share_adjustments}，含沖銷列）。
     */
    private static final String ADJUSTMENT_BRANCH = """
        select s.uuid as id, s.type::varchar as kind, s.asset_id, s.effective_at as occurred_at,
               s.effective_date, s.created_at, s.quantity_delta as quantity, null::numeric as price,
               null::numeric as fee, null::numeric as amount, null::numeric as withholding_tax,
               s.ratio_new, s.ratio_old, s.note,
               o.uuid as reversal_of_id, o.effective_at as reversal_of_occurred_at,
               o.effective_date as reversal_of_effective_date,
               exists (select 1 from share_adjustments r where r.reversal_of_id = s.id) as reversed,
               (s.reversal_of_id is null
                and not exists (select 1 from share_adjustments r where r.reversal_of_id = s.id)
                and not exists (
                    select 1 from share_adjustments l
                    where l.user_id = s.user_id and l.asset_id = s.asset_id
                      and l.reversal_of_id is null
                      and not exists (select 1 from share_adjustments lr where lr.reversal_of_id = l.id)
                      and (l.effective_at, l.id) > (s.effective_at, s.id))) as reversible
        from share_adjustments s
        left join share_adjustments o on o.id = s.reversal_of_id and o.user_id = s.user_id
        where s.user_id = :userId
        """;

    private final JdbcClient jdbcClient;

    public JdbcActivityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ActivityRow> list(Long userId, ActivityQuery query) {
        Statement statement = listStatement(userId, query);
        return jdbcClient.sql(statement.sql())
            .params(statement.params())
            .query(JdbcActivityRepository::mapRow)
            .list();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long count(Long userId, ActivityQuery query) {
        Statement statement = countStatement(userId, query);
        return jdbcClient.sql(statement.sql())
            .params(statement.params())
            .query(Long.class)
            .single();
    }

    /**
     * 組出分頁列表的 SQL 與參數。
     *
     * <p>排序為「所選欄位 {@code nulls last}、{@code created_at}、{@code id}（UUID）」，方向一致：
     * tie-breaker 讓同值的列在跨頁時有唯一順序，不會跳列或重複。{@code nulls last} 兩個方向都套用
     * （契約：依 {@code total} 排序時沒有金額的股數調整、依 {@code quantity} 排序時沒有數量的股利排最後）。</p>
     *
     * @param userId 擁有者 id
     * @param query  查詢條件
     * @return SQL 與具名參數
     */
    static Statement listStatement(Long userId, ActivityQuery query) {
        Filter filter = filter(userId, query);
        String direction = query.direction().sqlKeyword();
        Map<String, Object> params = new LinkedHashMap<>(filter.params());
        params.put("limit", query.size());
        params.put("offset", (long) query.page() * query.size());
        return new Statement("select a.* from (" + filter.union() + ") a" + filter.where()
            + " order by " + query.sort().sqlFragment() + " " + direction + " nulls last, "
            + "a.created_at " + direction + ", a.id " + direction + " "
            + "limit :limit offset :offset", params);
    }

    /**
     * 組出總筆數的 SQL 與參數；篩選與 {@link #listStatement} 相同，不含排序與分頁。
     *
     * @param userId 擁有者 id
     * @param query  查詢條件
     * @return SQL 與具名參數
     */
    static Statement countStatement(Long userId, ActivityQuery query) {
        Filter filter = filter(userId, query);
        return new Statement("select count(*) from (" + filter.union() + ") a" + filter.where(), filter.params());
    }

    /**
     * 依查詢條件挑選分支並組出外層條件。
     *
     * <p>種類篩選只決定拼接哪些<strong>寫死</strong>的分支：股利只拼 {@link #DIVIDEND_BRANCH}、股數調整只拼
     * {@link #ADJUSTMENT_BRANCH}、買入 / 賣出只拼 {@link #TRADE_BRANCH} 並追加 {@code t.type = :tradeType}
     * （值以參數繫結）。標的與時間區間作用於外層別名 {@code a}（三個分支統一輸出的欄位）：
     * {@code occurred_at} 為半開區間（下界含、上界不含），與 {@code GET /trades} 的 {@code executed_at} 語意相同；
     * PostgreSQL 會把這類簡單條件推進 UNION ALL 的每個分支，不影響索引使用。</p>
     *
     * @param userId 擁有者 id
     * @param query  查詢條件
     * @return 分支 SQL、外層 where 子句（無條件時為空字串）與具名參數
     */
    private static Filter filter(Long userId, ActivityQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        List<String> branches = new ArrayList<>();
        ActivityTypeFilter type = query.type().orElse(null);
        if (type == null) {
            branches.add(TRADE_BRANCH);
            branches.add(DIVIDEND_BRANCH);
            branches.add(ADJUSTMENT_BRANCH);
        } else {
            switch (type) {
                case BUY, SELL -> {
                    branches.add(TRADE_BRANCH + "  and t.type = :tradeType\n");
                    params.put("tradeType", type.name());
                }
                case DIVIDEND -> branches.add(DIVIDEND_BRANCH);
                case SHARE_ADJUSTMENT -> branches.add(ADJUSTMENT_BRANCH);
            }
        }
        List<String> conditions = new ArrayList<>();
        if (query.assetIdOrNull() != null) {
            conditions.add("a.asset_id = :assetId");
            params.put("assetId", query.assetIdOrNull());
        }
        if (query.from() != null) {
            conditions.add("a.occurred_at >= :dateFrom");
            params.put("dateFrom", query.from());
        }
        if (query.to() != null) {
            conditions.add("a.occurred_at < :dateTo");
            params.put("dateTo", query.to());
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        return new Filter(String.join(" union all ", branches), where, params);
    }

    /**
     * 分支選擇與外層條件的組合結果。
     *
     * @param union  以 {@code union all} 串起的分支 SQL
     * @param where  外層 where 子句（含前導空白）；無條件時為空字串
     * @param params 具名參數（含 {@code userId}）
     */
    private record Filter(String union, String where, Map<String, Object> params) {
    }

    /**
     * 組好的 SQL 與具名參數；SQL 只由寫死的片段組成，參數承載所有值。
     *
     * @param sql    SQL 文字
     * @param params 具名參數
     */
    record Statement(String sql, Map<String, Object> params) {
    }

    /**
     * 把結果集的當前列對應成 {@link ActivityRow}。
     *
     * @param rs     已定位在目標列的結果集
     * @param rowNum 列序號（RowMapper 介面要求，未使用）
     * @return 對應的活動列
     * @throws SQLException 讀取欄位失敗時
     */
    static ActivityRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityRow(
            rs.getObject("id", UUID.class),
            ActivityKind.valueOf(rs.getString("kind")),
            rs.getLong("asset_id"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("fee"),
            rs.getBigDecimal("amount"),
            rs.getBigDecimal("withholding_tax"),
            /* 可為 NULL 的整數一律用 getObject(欄位, 類別)：getInt 對 NULL 回 0，會讓非拆股列被讀成比例 0:0。 */
            rs.getObject("ratio_new", Integer.class),
            rs.getObject("ratio_old", Integer.class),
            rs.getString("note"),
            rs.getObject("reversal_of_id", UUID.class),
            rs.getObject("reversal_of_occurred_at", OffsetDateTime.class),
            rs.getObject("reversal_of_effective_date", LocalDate.class),
            rs.getBoolean("reversed"),
            rs.getBoolean("reversible")
        );
    }
}
