package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * transactions 冪等鍵在資料庫層的約束整合測試。
 *
 * <p>這個測試鎖的契約是：<b>交易寫入的冪等防護必須由資料庫的唯一約束強制，不是由應用層邏輯強制</b>
 * （judgment.md §5、CONTEXT D-08）。因此每一條都以 {@link JdbcClient} 直接對真實 PostgreSQL 下 SQL，
 * 刻意繞過 TradingService —— 應用層的檢查在併發連點時可以同時通過，唯一約束是唯一擋得住的一層。</p>
 *
 * <p>三個必須同時成立、彼此會互相掩蓋的性質：</p>
 * <ol>
 *   <li><b>唯一性</b>：同一 user 用同一個 key 插第二列必須被 DB 拒絕。</li>
 *   <li><b>部分索引</b>：索引必須帶 {@code WHERE idempotency_key IS NOT NULL}。少了這個 predicate，
 *       應用層的 {@code INSERT ... ON CONFLICT (user_id, idempotency_key) WHERE ...} 無法推斷出此索引；
 *       而且「多列 NULL 可共存」在非部分索引下本來就成立，所以那條測試抓不到這個錯 ——
 *       只有直接斷言 {@code pg_indexes.indexdef} 含 WHERE 才抓得到。</li>
 *   <li><b>跨使用者隔離</b>：索引必須是 {@code (user_id, idempotency_key)} 兩欄。若誤寫成單欄唯一，
 *       A 送出的 key 會命中 B 的交易，把 B 的交易內容回給 A（威脅 T-04-02）。</li>
 * </ol>
 *
 * <p><b>後半段（repository 層）</b>改以 {@link TradingRepository} 這個真實 bean 對真實 PostgreSQL 驗證，
 * 鎖的是「上面那組 DB 約束真的被應用層的 SQL 用對了」：{@code ON CONFLICT} 的 predicate 是否逐字命中該部分索引
 * （推斷失敗會直接拋 {@code there is no unique or exclusion constraint matching the ON CONFLICT specification}，
 * 而不是靜默降級），以及衝突時是否回空 {@link Optional} 而非拋例外。這兩件事<b>只能</b>用真實 PostgreSQL 證明——
 * H2 不支援部分唯一索引的 {@code ON CONFLICT} 推斷。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@DisplayName("transactions 冪等鍵的資料庫層約束")
class TransactionsIdempotencyIT extends ContainerIT {

    /**
     * 索引名稱必須與 migration 逐字一致：應用層的 ON CONFLICT 推斷依賴同一組欄位與 predicate。
     */
    private static final String INDEX_NAME = "uk_transactions_user_idempotency";

    /**
     * 直接對資料庫下 SQL 的用戶端，繞過應用層以驗證 DB 層強制。
     */
    @Autowired
    JdbcClient jdbcClient;

    /**
     * 受測的真實 repository bean。後三條刻意不再直接下 SQL —— 要驗的正是 repository 自己組出來的 SQL。
     */
    @Autowired
    TradingRepository tradingRepository;

    @Test
    @DisplayName("transactions 有 idempotency_key 欄位，可為 NULL 且長度上限為 128")
    void idempotencyKeyColumnExistsAsNullableVarchar128() {
        List<ColumnInfo> columns = jdbcClient.sql("""
                SELECT is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_name = 'transactions' AND column_name = 'idempotency_key'
                """)
            .query((rs, rowNum) -> new ColumnInfo(rs.getString("is_nullable"), rs.getInt("character_maximum_length")))
            .list();

        assertThat(columns)
            .as("transactions.idempotency_key 欄位必須存在且恰好一個")
            .hasSize(1);
        assertThat(columns.getFirst().isNullable())
            .as("V11 之前的既有交易沒有 key 也無法回填，欄位必須可為 NULL")
            .isEqualTo("YES");
        assertThat(columns.getFirst().maxLength())
            .as("必須有長度上限（DoS 面），且大於 UUID 的 36 字元")
            .isEqualTo(128);
    }

    @Test
    @DisplayName("uk_transactions_user_idempotency 是帶 WHERE 條件的部分唯一索引")
    void idempotencyIndexIsPartialAndUnique() {
        List<String> indexDefs = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'transactions' AND indexname = :indexName
                """)
            .param("indexName", INDEX_NAME)
            .query(String.class)
            .list();

        assertThat(indexDefs)
            .as("索引 %s 必須存在", INDEX_NAME)
            .hasSize(1);
        assertThat(indexDefs.getFirst())
            .as("必須同時是 UNIQUE 且為部分索引（帶 WHERE），否則應用層的 ON CONFLICT 推斷不出此索引")
            .contains("UNIQUE")
            .contains("WHERE");
    }

    @Test
    @DisplayName("同一 user 用同一個冪等鍵插第二列會被 DB 拒絕")
    void sameUserSameKeyIsRejectedByDatabase() {
        Long userId = seedUser();
        Long assetId = aaplAssetId();
        String key = "dup-key-" + UUID.randomUUID();

        insertTransactionWithKey(userId, assetId, key);

        assertThatThrownBy(() -> insertTransactionWithKey(userId, assetId, key))
            .as("唯一約束必須在 DB 層擋下重複的 (user_id, idempotency_key)")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("同一 user 的多列 NULL 冪等鍵可以共存")
    void multipleNullKeysCoexistForSameUser() {
        Long userId = seedUser();
        Long assetId = aaplAssetId();

        insertTransactionWithNullKey(userId, assetId);
        insertTransactionWithNullKey(userId, assetId);

        Long count = jdbcClient.sql("""
                SELECT COUNT(*) FROM transactions
                WHERE user_id = :userId AND idempotency_key IS NULL
                """)
            .param("userId", userId)
            .query(Long.class)
            .single();

        assertThat(count)
            .as("V11 之前的既有交易 key 為 NULL，必須能無限共存")
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("不同 user 用同一個冪等鍵各自建立成功（跨使用者隔離）")
    void sameKeyAcrossDifferentUsersIsAllowed() {
        Long firstUserId = seedUser();
        Long secondUserId = seedUser();
        Long assetId = aaplAssetId();
        String sharedKey = "shared-key-" + UUID.randomUUID();

        insertTransactionWithKey(firstUserId, assetId, sharedKey);
        insertTransactionWithKey(secondUserId, assetId, sharedKey);

        Long count = jdbcClient.sql("SELECT COUNT(*) FROM transactions WHERE idempotency_key = :key")
            .param("key", sharedKey)
            .query(Long.class)
            .single();

        assertThat(count)
            .as("唯一約束的維度必須是 (user_id, idempotency_key)，A 的 key 不得命中 B 的交易")
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("insertTransactionIfAbsent 帶新冪等鍵時建立交易並回傳該筆")
    void insertTransactionIfAbsentReturnsRowForNewKey() {
        Long userId = seedUser();
        Long assetId = aaplAssetId();
        String key = "repo-new-" + UUID.randomUUID();

        Optional<TradeTransaction> inserted = tradingRepository.insertTransactionIfAbsent(
            newTransaction(userId, assetId, key));

        assertThat(inserted)
            .as("未使用過的冪等鍵必須真的建立一筆交易")
            .isPresent();
        assertThat(inserted.orElseThrow().id())
            .as("RETURNING 必須回填資料庫產生的主鍵，否則 controller 無從回傳 trade id")
            .isNotNull();
        assertThat(inserted.orElseThrow().idempotencyKey())
            .as("寫進去的 key 必須從 RETURNING 讀得回來")
            .isEqualTo(key);
    }

    /**
     * 這條同時鎖住兩件事，缺一不可：
     * <ol>
     *   <li>{@code ON CONFLICT (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL} 的 predicate
     *       逐字命中 V11 的部分索引 —— 推斷失敗會在此直接拋
     *       {@code there is no unique or exclusion constraint matching the ON CONFLICT specification}。</li>
     *   <li>{@code DO NOTHING} 的語意：衝突時回空 {@link Optional}、<b>不拋例外</b>（因此不會中止當前交易，
     *       04-03 才能在同一個 {@code @Transactional} 內接著重讀既有交易）。</li>
     * </ol>
     */
    @Test
    @DisplayName("同一 user 同一冪等鍵第二次呼叫回空 Optional、不拋例外且不建第二列")
    void insertTransactionIfAbsentIsNoOpOnDuplicateKey() {
        Long userId = seedUser();
        Long assetId = aaplAssetId();
        String key = "repo-dup-" + UUID.randomUUID();

        Optional<TradeTransaction> first = tradingRepository.insertTransactionIfAbsent(
            newTransaction(userId, assetId, key));
        Optional<TradeTransaction> second = tradingRepository.insertTransactionIfAbsent(
            newTransaction(userId, assetId, key));

        assertThat(first)
            .as("第一次必須成功建列")
            .isPresent();
        assertThat(second)
            .as("第二次必須靜默不建列（DO NOTHING）；若這裡拋例外，代表用成了 DO UPDATE 或推斷失敗")
            .isEmpty();

        Long count = jdbcClient.sql("""
                SELECT COUNT(*) FROM transactions
                WHERE user_id = :userId AND idempotency_key = :key
                """)
            .param("userId", userId)
            .param("key", key)
            .query(Long.class)
            .single();

        assertThat(count)
            .as("同一 (user_id, idempotency_key) 永遠只能有一列（T-04-01）")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("findByIdempotencyKey 查得回本人的交易，別的 user 用同一 key 查不到")
    void findByIdempotencyKeyIsScopedToOwner() {
        Long ownerId = seedUser();
        Long otherUserId = seedUser();
        Long assetId = aaplAssetId();
        String key = "repo-scope-" + UUID.randomUUID();

        tradingRepository.insertTransactionIfAbsent(newTransaction(ownerId, assetId, key));

        Optional<TradeTransaction> found = tradingRepository.findByIdempotencyKey(ownerId, key);

        assertThat(found)
            .as("本人以自己的 key 必須查得到既有交易")
            .isPresent();
        assertThat(found.orElseThrow().idempotencyKey())
            .as("查詢結果必須讀得回 idempotency_key")
            .isEqualTo(key);
        assertThat(found.orElseThrow().symbol())
            .as("join assets 必須仍在，否則 04-03 回傳既有交易時 symbol 會是 null")
            .isEqualTo("AAPL");

        assertThat(tradingRepository.findByIdempotencyKey(otherUserId, key))
            .as("T-04-02：查詢必須同時綁 user_id，A 的 key 不得命中 B 的交易")
            .isEmpty();
    }

    /**
     * 組出一筆待寫入的交易。{@code uuid} 每次都重新產生，模擬「同一個冪等鍵、不同次請求」——
     * 若第二次的衝突是撞在 uuid 而非 (user_id, idempotency_key) 上，這條測試就證明不了冪等索引。
     *
     * @param userId         使用者主鍵
     * @param assetId        資產主鍵
     * @param idempotencyKey 冪等鍵
     * @return 尚未持久化的交易（id / createdAt 由資料庫產生）
     */
    private TradeTransaction newTransaction(Long userId, Long assetId, String idempotencyKey) {
        return new TradeTransaction(
            null,
            UUID.randomUUID(),
            userId,
            assetId,
            "AAPL",
            TradeType.BUY,
            new BigDecimal("1"),
            new BigDecimal("100"),
            BigDecimal.ZERO,
            null,
            OffsetDateTime.now(),
            null,
            idempotencyKey
        );
    }

    /**
     * 種下一名測試專屬使用者，避免測試之間互相污染計數。
     *
     * @return 新使用者的主鍵
     */
    private Long seedUser() {
        return jdbcClient.sql("""
                INSERT INTO users(email, username, password_hash, role, status)
                VALUES (:email, :username, 'x', 'USER', 'ACTIVE')
                RETURNING id
                """)
            .param("email", "idempotency-" + UUID.randomUUID() + "@example.com")
            .param("username", "idempotency-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    /**
     * 取得 seed 資料中 AAPL 的資產主鍵。
     *
     * @return AAPL 的 asset id
     */
    private Long aaplAssetId() {
        return jdbcClient.sql("SELECT id FROM assets WHERE symbol = 'AAPL'")
            .query(Long.class)
            .single();
    }

    /**
     * 直接插入一筆帶冪等鍵的交易列。
     *
     * @param userId         使用者主鍵
     * @param assetId        資產主鍵
     * @param idempotencyKey 冪等鍵
     */
    private void insertTransactionWithKey(Long userId, Long assetId, String idempotencyKey) {
        jdbcClient.sql("""
                INSERT INTO transactions(user_id, asset_id, type, quantity, price, fee, executed_at, idempotency_key)
                VALUES (:userId, :assetId, 'BUY', 1, 100, 0, NOW(), :idempotencyKey)
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .param("idempotencyKey", idempotencyKey)
            .update();
    }

    /**
     * 直接插入一筆冪等鍵為 NULL 的交易列，模擬 V11 之前的既有資料。
     *
     * @param userId  使用者主鍵
     * @param assetId 資產主鍵
     */
    private void insertTransactionWithNullKey(Long userId, Long assetId) {
        jdbcClient.sql("""
                INSERT INTO transactions(user_id, asset_id, type, quantity, price, fee, executed_at, idempotency_key)
                VALUES (:userId, :assetId, 'BUY', 1, 100, 0, NOW(), NULL)
                """)
            .param("userId", userId)
            .param("assetId", assetId)
            .update();
    }

    /**
     * information_schema 查詢結果的載體。
     *
     * @param isNullable 欄位是否可為 NULL（'YES' / 'NO'）
     * @param maxLength  字元長度上限
     */
    private record ColumnInfo(String isNullable, int maxLength) {
    }
}
