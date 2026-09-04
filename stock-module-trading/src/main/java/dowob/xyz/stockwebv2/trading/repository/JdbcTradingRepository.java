package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 交易與持倉的 JDBC 資料存取實作。
 *
 * <p>本類是唯一組裝交易模組 SQL 的地方。動態部分（WHERE 條件、ORDER BY）一律來自
 * {@link TradeQuery} 內已通過白名單的型別化值：條件值走 JDBC 具名參數，排序片段取自 enum
 * 的寫死常數，請求字串永遠不會成為 SQL 文字（code-standards「絕對禁止串接 SQL」硬規則）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@Repository
public class JdbcTradingRepository implements TradingRepository {
    private static final String TRANSACTION_COLUMNS = """
        t.id, t.uuid, t.user_id, t.asset_id, a.symbol, t.type, t.quantity, t.price,
        t.fee, t.note, t.executed_at, t.created_at, t.idempotency_key
        """;

    private final JdbcClient jdbcClient;

    public JdbcTradingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 帶冪等鍵的交易寫入，也是本模組<strong>唯一</strong>的交易寫入路徑。
     *
     * <p>{@code on conflict} 的欄位組合與 {@code where} predicate 必須與 V11 的部分唯一索引
     * {@code uk_transactions_user_idempotency} <strong>逐字相同</strong>，否則 PostgreSQL 推斷不出要用哪個索引，
     * 會直接報 {@code there is no unique or exclusion constraint matching the ON CONFLICT specification}
     * ——不會靜默降級成一般 insert，所以推斷寫錯必定在測試中現形。</p>
     *
     * <p>衝突時只能是 {@code do nothing}，<strong>絕不可</strong>改用會就地改寫既有列的那個 {@code on conflict}
     * 分支：{@code transactions} 由 V8 的 {@code trg_transactions_no_update} 保護，任何 UPDATE 都會被 trigger
     * 擋下。而 {@code do nothing} 在衝突時 {@code returning} 回零列，故結尾必須是 {@code .optional()} 而不是
     * {@code .single()}（{@code .single()} 會拋 {@code EmptyResultDataAccessException}，
     * 等於把「冪等命中」這個正常結果誤報成系統錯誤）。</p>
     *
     * <p>{@code symbol} 不在 {@code returning} 內（本述句沒有 join assets），沿用呼叫端傳入的值。</p>
     *
     * @param transaction 待寫入的交易；{@code id} 與 {@code createdAt} 由資料庫產生
     * @return 實際建立的交易；冪等鍵已被同一使用者用過時回傳空 Optional
     */
    @Override
    public Optional<TradeTransaction> insertTransactionIfAbsent(TradeTransaction transaction) {
        return jdbcClient.sql("""
                insert into transactions (
                    uuid, user_id, asset_id, type, quantity, price, fee, note, executed_at, idempotency_key
                )
                values (
                    coalesce(:uuid, uuid_generate_v4()), :userId, :assetId, :type,
                    :quantity, :price, :fee, :note, :executedAt, :idempotencyKey
                )
                on conflict (user_id, idempotency_key) where idempotency_key is not null do nothing
                returning id, uuid, user_id, asset_id, type, quantity, price, fee, note,
                          executed_at, created_at, idempotency_key
                """)
            .param("uuid", transaction.uuid())
            .param("userId", transaction.userId())
            .param("assetId", transaction.assetId())
            .param("type", transaction.type().name())
            .param("quantity", transaction.quantity())
            .param("price", transaction.price())
            .param("fee", transaction.fee())
            .param("note", transaction.note())
            .param("executedAt", transaction.executedAt())
            .param("idempotencyKey", transaction.idempotencyKey())
            .query((rs, rowNum) -> new TradeTransaction(
                rs.getLong("id"),
                rs.getObject("uuid", java.util.UUID.class),
                rs.getLong("user_id"),
                rs.getLong("asset_id"),
                transaction.symbol(),
                TradeType.valueOf(rs.getString("type")),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("fee"),
                rs.getString("note"),
                rs.getObject("executed_at", java.time.OffsetDateTime.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getString("idempotency_key")
            ))
            .optional();
    }

    /**
     * 以冪等鍵查出該使用者既有的交易，供冪等重送時回傳原本的結果。
     *
     * <p>{@code where} 同時綁 {@code t.user_id} 與 {@code t.idempotency_key}：冪等鍵只在單一使用者範圍內唯一，
     * 少了 {@code user_id} 會讓 A 的 key 命中 B 的交易（威脅 T-04-02）。join {@code assets} 是為了取回
     * {@code symbol}，與 {@link #listTransactions(TradeQuery)} 用同一組 {@code TRANSACTION_COLUMNS}。</p>
     *
     * <p>刻意不加 {@code for update}：要查的列可能還不存在，而列鎖對不存在的列不生效（{@code TradingApiIT}
     * 的註解指出過同一件事），加了只會製造已防住併發的錯覺。併發由部分唯一索引擋，不是由這個查詢擋。</p>
     *
     * @param userId         交易擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有交易；查無此 key 時回傳空 Optional
     */
    @Override
    public Optional<TradeTransaction> findByIdempotencyKey(Long userId, String idempotencyKey) {
        return jdbcClient.sql("select " + TRANSACTION_COLUMNS + """
                from transactions t
                join assets a on a.id = t.asset_id
                where t.user_id = :userId and t.idempotency_key = :idempotencyKey
                """)
            .param("userId", userId)
            .param("idempotencyKey", idempotencyKey)
            .query(this::mapTransaction)
            .optional();
    }

    @Override
    public Optional<Holding> findHoldingForUpdate(Long userId, Long assetId) {
        return jdbcClient.sql("""
                select id, user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated
                from holdings
                where user_id = :userId and asset_id = :assetId
                for update
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .query(this::mapHolding)
            .optional();
    }

    @Override
    public Optional<Holding> insertHoldingIfAbsent(Holding holding) {
        // ON CONFLICT DO NOTHING 讓併發首次建倉不會拋唯一鍵例外（不會污染交易）；
        // 回傳空 Optional 表示已被他交易插入，呼叫端重讀後改走 update 併倉。
        return jdbcClient.sql("""
                insert into holdings (user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated)
                values (:userId, :assetId, :totalQuantity, :avgCost, :realizedPnl, 0, :lastUpdated)
                on conflict (user_id, asset_id) do nothing
                returning id, user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated
                """)
            .param("userId", holding.userId())
            .param("assetId", holding.assetId())
            .param("totalQuantity", holding.totalQuantity())
            .param("avgCost", holding.avgCost())
            .param("realizedPnl", holding.realizedPnl())
            .param("lastUpdated", holding.lastUpdated())
            .query(this::mapHolding)
            .optional();
    }

    @Override
    public Holding updateHolding(Holding holding) {
        Optional<Holding> updated = jdbcClient.sql("""
                update holdings
                set total_quantity = :totalQuantity,
                    avg_cost = :avgCost,
                    realized_pnl = :realizedPnl,
                    version = version + 1,
                    last_updated = :lastUpdated
                where id = :id and version = :version
                returning id, user_id, asset_id, total_quantity, avg_cost, realized_pnl, version, last_updated
                """)
            .param("id", holding.id())
            .param("version", holding.version())
            .param("totalQuantity", holding.totalQuantity())
            .param("avgCost", holding.avgCost())
            .param("realizedPnl", holding.realizedPnl())
            .param("lastUpdated", holding.lastUpdated())
            .query(this::mapHolding)
            .optional();
        return updated.orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CONFLICT, ErrorCode.TRADE_CONFLICT.defaultMessage()));
    }

    /**
     * 依查詢條件取回一頁交易，並回報符合同一組條件的總筆數。
     *
     * <p>count 與 list 是兩道獨立述句，必須在<strong>同一個交易</strong>中執行才能共用快照，
     * 否則併發寫入落在兩者之間時 {@code totalElements} 會與 {@code items} 漂移。呼叫端
     * {@code TradingService#listTrades} 已標記為 {@code @Transactional(readOnly = true)}。</p>
     *
     * @param query 已通過白名單驗證的型別化查詢物件
     * @return 該頁交易與符合條件的總筆數
     */
    @Override
    public PageResponse<TradeTransaction> listTransactions(TradeQuery query) {
        Filter filter = buildFilter(query);
        long offset = (long) query.page() * query.size();
        JdbcClient.StatementSpec listSpec = jdbcClient.sql("select " + TRANSACTION_COLUMNS + """
                from transactions t
                join assets a on a.id = t.asset_id
                """ + filter.whereClause() + orderByClause(query) + """
                limit :limit offset :offset
                """)
            .params(filter.params())
            .param("limit", query.size())
            .param("offset", offset);
        JdbcClient.StatementSpec countSpec = jdbcClient.sql("select count(*) from transactions t " + filter.whereClause())
            .params(filter.params());
        long total = countSpec.query(Long.class).single();
        return PageResponse.of(listSpec.query(this::mapTransaction).list(), query.page(), query.size(), total);
    }

    /**
     * 組裝交易查詢的 WHERE 子句與對應具名參數，作為 list 與 count 的<strong>單一來源</strong>。
     *
     * <p>先前 list 與 count 各自持有一份重複的 WHERE 字串，任一邊新增篩選條件都會讓
     * {@code totalElements} 與 {@code items} 漂移；改由本方法統一產出後兩邊共用，
     * 使兩者<strong>在條件定義上</strong>不可能分歧。所有值一律走具名參數，
     * SQL 文字不含任何請求輸入。</p>
     *
     * <p>注意這只消除了「條件寫錯」這一種漂移來源。兩道述句仍是分別執行的，因此還需要
     * 呼叫端以唯讀交易包住，才能讓兩者讀到同一個快照——見
     * {@link #listTransactions(TradeQuery)}。</p>
     *
     * <p><strong>不變量：本方法產出的條件只能引用 {@code t.*}。</strong>list 的 FROM 多一個
     * {@code join assets a}（為了 SELECT 出 {@code a.symbol}），count 則刻意不 join——
     * {@code transactions.asset_id} 是 {@code NOT NULL REFERENCES assets(id)}，inner join
     * 不可能改變列數，替 count 加上 join 只是讓它白白變慢。代價是這裡若加入一條引用
     * {@code a.*} 的條件，count 會在執行期炸 {@code missing FROM-clause entry for table "a"}。
     * 需要按資產屬性篩選時，請比照現有 {@code symbol}：在 service 層先解析成
     * {@code asset_id} 再進來，而不是在此 join。</p>
     *
     * @param query 已驗證的查詢物件
     * @return WHERE 子句字串與其具名參數
     */
    private Filter buildFilter(TradeQuery query) {
        StringBuilder where = new StringBuilder("where t.user_id = :userId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", query.userId());
        if (query.assetId() != null) {
            where.append(" and t.asset_id = :assetId");
            params.put("assetId", query.assetId());
        }
        if (query.type() != null) {
            where.append(" and t.type = :type");
            params.put("type", query.type().name());
        }
        if (query.dateFrom() != null) {
            where.append(" and t.executed_at >= :dateFrom");
            params.put("dateFrom", query.dateFrom());
        }
        if (query.dateTo() != null) {
            where.append(" and t.executed_at < :dateTo");
            params.put("dateTo", query.dateTo());
        }
        return new Filter(where.append(' ').toString(), params);
    }

    /**
     * 組裝 ORDER BY 子句。排序鍵與方向皆取自 enum 白名單的寫死片段，請求字串永不進入 SQL；
     * 一律附加 {@code t.id} 作為 tie-breaker，確保排序值相同時分頁結果仍具決定性
     * （否則翻頁可能重複或遺漏資料）。
     *
     * @param query 已驗證的查詢物件
     * @return 完整的 ORDER BY 子句
     */
    private String orderByClause(TradeQuery query) {
        String direction = query.direction().sqlKeyword();
        return "order by " + query.sortKey().sqlFragment() + " " + direction + ", t.id " + direction + " ";
    }

    /**
     * 動態 WHERE 的組裝結果。
     *
     * @param whereClause WHERE 子句字串（僅含寫死條件與具名參數佔位符）
     * @param params      具名參數值
     */
    private record Filter(String whereClause, Map<String, Object> params) {
    }

    @Override
    public List<HoldingPosition> listHoldings(Long userId) {
        return jdbcClient.sql("""
                select h.id, h.user_id, h.asset_id, a.uuid as asset_uuid, a.symbol, a.name,
                       h.total_quantity, h.avg_cost, h.realized_pnl, h.version, h.last_updated
                from holdings h
                join assets a on a.id = h.asset_id
                where h.user_id = :userId and h.total_quantity > 0
                order by a.symbol asc
                """)
            .param("userId", userId)
            .query(this::mapPosition)
            .list();
    }

    @Override
    public BigDecimal sumRealizedPnl(Long userId) {
        return jdbcClient.sql("""
                select coalesce(sum(realized_pnl), 0)
                from holdings
                where user_id = :userId
                """)
            .param("userId", userId)
            .query(BigDecimal.class)
            .single();
    }

    private TradeTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new TradeTransaction(
            rs.getLong("id"),
            rs.getObject("uuid", java.util.UUID.class),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getString("symbol"),
            TradeType.valueOf(rs.getString("type")),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("fee"),
            rs.getString("note"),
            rs.getObject("executed_at", java.time.OffsetDateTime.class),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getString("idempotency_key")
        );
    }

    private Holding mapHolding(ResultSet rs, int rowNum) throws SQLException {
        return new Holding(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getBigDecimal("total_quantity"),
            rs.getBigDecimal("avg_cost"),
            rs.getBigDecimal("realized_pnl"),
            rs.getInt("version"),
            rs.getObject("last_updated", java.time.OffsetDateTime.class)
        );
    }

    private HoldingPosition mapPosition(ResultSet rs, int rowNum) throws SQLException {
        return new HoldingPosition(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("asset_id"),
            rs.getObject("asset_uuid", java.util.UUID.class),
            rs.getString("symbol"),
            rs.getString("name"),
            rs.getBigDecimal("total_quantity"),
            rs.getBigDecimal("avg_cost"),
            rs.getBigDecimal("realized_pnl"),
            rs.getInt("version"),
            rs.getObject("last_updated", java.time.OffsetDateTime.class)
        );
    }
}
