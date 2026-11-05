package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * share_adjustments 的資料庫層保護整合測試（D-13；V17）。
 *
 * <p>鎖三件事：其一，帳本 append-only——UPDATE / DELETE / TRUNCATE 一律被 trigger 拒絕；
 * 其二，沖銷規則由資料庫強制——沖銷列必須指向<strong>同一使用者、同一標的</strong>、本身不是沖銷的原列，
 * 且 type / ratio / quantity_delta / effective_date / effective_at 與原列相同，同一原列只能被沖銷一次；
 * 其三，形狀與類型 CHECK（配股無比例且 ΔQ 為正、拆股兩比例皆有且不相等、ΔQ 非零）與以使用者為範圍的冪等鍵唯一約束。</p>
 *
 * <p>全部測試以 {@link JdbcClient} 直接下 SQL，繞過應用層以證明是 DB 層在擋；測試內<strong>不得</strong>
 * 自行建立 trigger 或更動表結構。帳本不可 DELETE，測試資料以每個測試各自新建的使用者隔離，不做清理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整的資料庫層保護")
class ShareAdjustmentsAppendOnlyIT extends ContainerIT {

    private static final String APPEND_ONLY_MESSAGE = "share_adjustments is append-only";

    private static final String REVERSAL_REJECTED = "share adjustment reversal rejected";

    private static final String VALUES_MISMATCH = "values do not match the original adjustment";

    private static final String SPLIT = "SPLIT";

    private static final String STOCK_DIVIDEND = "STOCK_DIVIDEND";

    /**
     * 原列的除權日。
     */
    private static final LocalDate EFFECTIVE_DATE = LocalDate.parse("2026-10-29");

    /**
     * 原列的生效瞬間（美股 2026-10-29 交易日起點，紐約午夜 EDT）。
     */
    private static final OffsetDateTime EFFECTIVE_AT = OffsetDateTime.parse("2026-10-29T04:00:00Z");

    /**
     * 直接對資料庫下 SQL 的用戶端，繞過應用層以驗證 DB 層強制。
     */
    @Autowired
    JdbcClient jdbcClient;

    /**
     * 原列擁有者。
     */
    private Long ownerId;

    /**
     * 另一名使用者，用於「沖銷他人紀錄」與「冪等鍵跨使用者」的案例。
     */
    private Long otherUserId;

    /**
     * 原列的標的（seed 的 AAPL）。
     */
    private Long aaplId;

    /**
     * 另一個標的（seed 的 MSFT），用於「沖銷時換標的」的案例。
     */
    private Long msftId;

    /**
     * 原列：owner 的 AAPL 拆股 4:1，ΔQ = +300。
     */
    private Long originalId;

    /**
     * 每個測試前種下兩名使用者與一筆原列。
     */
    @BeforeEach
    void seedOriginalAdjustment() {
        ownerId = insertUser();
        otherUserId = insertUser();
        aaplId = assetId("AAPL");
        msftId = assetId("MSFT");
        originalId = insertAdjustment(new Row(ownerId, aaplId, SPLIT, 4, 1, "300"));
    }

    @Test
    @DisplayName("對股數調整執行 UPDATE 會被 DB trigger 拒絕")
    void updateIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE share_adjustments SET note = 'tampered' WHERE id = :id")
            .param("id", originalId)
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("對股數調整執行 DELETE 會被 DB trigger 拒絕")
    void deleteIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM share_adjustments WHERE id = :id")
            .param("id", originalId)
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("對股數調整執行 TRUNCATE 會被 DB trigger 拒絕")
    void truncateIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("TRUNCATE TABLE share_adjustments").update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(APPEND_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("同使用者、同標的、值全部相同的沖銷列可以寫入（quantity_delta scale 不同但數值相等亦可）")
    void validReversalIsAccepted() {
        Long reversalId = insertAdjustment(new Row(ownerId, aaplId, SPLIT, 4, 1, "300.000").reversing(originalId));

        Long storedReversalOf = jdbcClient.sql("SELECT reversal_of_id FROM share_adjustments WHERE id = :id")
            .param("id", reversalId)
            .query(Long.class)
            .single();
        assertThat(storedReversalOf).isEqualTo(originalId);
    }

    @Test
    @DisplayName("同一原列被沖銷第二次會被 uk_share_adjustments_reversal_of 拒絕（併發雙沖銷的最後防線）")
    void secondReversalOfSameAdjustmentIsRejected() {
        insertAdjustment(new Row(ownerId, aaplId, SPLIT, 4, 1, "300").reversing(originalId));

        assertThatThrownBy(() ->
            insertAdjustment(new Row(ownerId, aaplId, SPLIT, 4, 1, "300").reversing(originalId)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_share_adjustments_reversal_of");
    }

    @Test
    @DisplayName("沖銷一筆不存在的原列會被拒絕")
    void reversalOfNonexistentAdjustmentIsRejected() {
        Long missingId = jdbcClient.sql("SELECT COALESCE(MAX(id), 0) + 1000000 FROM share_adjustments")
            .query(Long.class)
            .single();

        assertReversalRejected(new Row(ownerId, aaplId, SPLIT, 4, 1, "300").reversing(missingId),
            "original adjustment does not exist");
    }

    @Test
    @DisplayName("沖銷他人的原列會被拒絕")
    void reversalOfAnotherUsersAdjustmentIsRejected() {
        assertReversalRejected(new Row(otherUserId, aaplId, SPLIT, 4, 1, "300").reversing(originalId),
            "original adjustment belongs to another user");
    }

    @Test
    @DisplayName("沖銷列的標的與原列不同會被拒絕")
    void reversalWithDifferentAssetIsRejected() {
        assertReversalRejected(new Row(ownerId, msftId, SPLIT, 4, 1, "300").reversing(originalId),
            "asset does not match the original adjustment");
    }

    @Test
    @DisplayName("沖銷一筆「沖銷列」會被拒絕")
    void reversalOfReversalIsRejected() {
        Long reversalId = insertAdjustment(new Row(ownerId, aaplId, SPLIT, 4, 1, "300").reversing(originalId));

        assertReversalRejected(new Row(ownerId, aaplId, SPLIT, 4, 1, "300").reversing(reversalId),
            "original adjustment is itself a reversal");
    }

    @Test
    @DisplayName("沖銷列的 quantity_delta 與原列不同會被拒絕")
    void reversalWithMismatchedQuantityDeltaIsRejected() {
        assertReversalRejected(new Row(ownerId, aaplId, SPLIT, 4, 1, "299").reversing(originalId), VALUES_MISMATCH);
    }

    @Test
    @DisplayName("沖銷列的比例與原列不同會被拒絕")
    void reversalWithMismatchedRatioIsRejected() {
        assertReversalRejected(new Row(ownerId, aaplId, SPLIT, 3, 1, "300").reversing(originalId), VALUES_MISMATCH);
    }

    @Test
    @DisplayName("沖銷列的除權日與原列不同會被拒絕")
    void reversalWithMismatchedEffectiveDateIsRejected() {
        assertReversalRejected(new Row(ownerId, aaplId, SPLIT, 4, 1, "300")
            .at(EFFECTIVE_DATE.minusDays(1), EFFECTIVE_AT)
            .reversing(originalId), VALUES_MISMATCH);
    }

    @Test
    @DisplayName("沖銷列的生效瞬間與原列不同會被拒絕")
    void reversalWithMismatchedEffectiveAtIsRejected() {
        assertReversalRejected(new Row(ownerId, aaplId, SPLIT, 4, 1, "300")
            .at(EFFECTIVE_DATE, EFFECTIVE_AT.plusHours(1))
            .reversing(originalId), VALUES_MISMATCH);
    }

    @Test
    @DisplayName("沖銷列的 type 與原列不同會被拒絕（以配股沖銷拆股）")
    void reversalWithMismatchedTypeIsRejected() {
        assertReversalRejected(new Row(ownerId, aaplId, STOCK_DIVIDEND, null, null, "300").reversing(originalId),
            VALUES_MISMATCH);
    }

    @Test
    @DisplayName("配股帶比例會被 ck_share_adjustments_shape 拒絕")
    void stockDividendWithRatioIsRejected() {
        assertShapeRejected(new Row(ownerId, aaplId, STOCK_DIVIDEND, 2, 1, "50"));
    }

    @Test
    @DisplayName("拆股缺任一比例會被 ck_share_adjustments_shape 拒絕（CHECK 對 NULL 視為通過，須明寫 NOT NULL）")
    void splitWithoutRatioIsRejected() {
        assertShapeRejected(new Row(ownerId, aaplId, SPLIT, 4, null, "300"));
        assertShapeRejected(new Row(ownerId, aaplId, SPLIT, null, 1, "300"));
        assertShapeRejected(new Row(ownerId, aaplId, SPLIT, null, null, "300"));
    }

    @Test
    @DisplayName("拆股兩比例相等會被 ck_share_adjustments_shape 拒絕")
    void splitWithEqualRatioIsRejected() {
        assertShapeRejected(new Row(ownerId, aaplId, SPLIT, 3, 3, "300"));
    }

    @Test
    @DisplayName("quantity_delta 為 0（拆股與配股）或配股為負會被 ck_share_adjustments_shape 拒絕")
    void zeroOrNegativeDividendDeltaIsRejected() {
        assertShapeRejected(new Row(ownerId, aaplId, SPLIT, 4, 1, "0"));
        assertShapeRejected(new Row(ownerId, aaplId, STOCK_DIVIDEND, null, null, "0"));
        assertShapeRejected(new Row(ownerId, aaplId, STOCK_DIVIDEND, null, null, "-1"));
    }

    @Test
    @DisplayName("合股的負 quantity_delta 可以寫入")
    void reverseSplitWithNegativeDeltaIsAccepted() {
        Long id = insertAdjustment(new Row(ownerId, aaplId, SPLIT, 1, 10, "-94.5"));

        BigDecimal stored = jdbcClient.sql("SELECT quantity_delta FROM share_adjustments WHERE id = :id")
            .param("id", id)
            .query(BigDecimal.class)
            .single();
        assertThat(stored).isEqualByComparingTo("-94.5");
    }

    @Test
    @DisplayName("白名單外的 type（'DIV'）會被 ck_share_adjustments_type 拒絕")
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> insertAdjustment(new Row(ownerId, aaplId, "DIV", null, null, "5")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_share_adjustments_type");
    }

    @Test
    @DisplayName("同一使用者的同一把冪等鍵寫第二列會被 uk_share_adjustments_user_idempotency 拒絕")
    void duplicateIdempotencyKeyForSameUserIsRejected() {
        String key = "dup-" + UUID.randomUUID();
        insertAdjustment(new Row(ownerId, aaplId, STOCK_DIVIDEND, null, null, "5").withKey(key));

        assertThatThrownBy(() ->
            insertAdjustment(new Row(ownerId, aaplId, STOCK_DIVIDEND, null, null, "5").withKey(key)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_share_adjustments_user_idempotency");
    }

    @Test
    @DisplayName("不同使用者使用同一把冪等鍵可以並存（冪等以使用者為範圍）")
    void sameIdempotencyKeyAcrossUsersCoexists() {
        String key = "shared-" + UUID.randomUUID();
        insertAdjustment(new Row(ownerId, aaplId, STOCK_DIVIDEND, null, null, "5").withKey(key));
        insertAdjustment(new Row(otherUserId, aaplId, STOCK_DIVIDEND, null, null, "5").withKey(key));

        Integer rows = jdbcClient.sql("SELECT count(*) FROM share_adjustments WHERE idempotency_key = :key")
            .param("key", key)
            .query(Integer.class)
            .single();
        assertThat(rows).isEqualTo(2);
    }

    private void assertReversalRejected(Row row, String reason) {
        assertThatThrownBy(() -> insertAdjustment(row))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining(REVERSAL_REJECTED)
            .hasMessageContaining(reason);
    }

    private void assertShapeRejected(Row row) {
        assertThatThrownBy(() -> insertAdjustment(row))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_share_adjustments_shape");
    }

    /**
     * 以 SQL 種一名使用者。
     *
     * @return 使用者主鍵
     */
    private Long insertUser() {
        return jdbcClient.sql("""
                INSERT INTO users(email, username, password_hash, role, status)
                VALUES (:email, :username, 'x', 'USER', 'ACTIVE')
                RETURNING id
                """)
            .param("email", "share-adjustment-" + UUID.randomUUID() + "@example.com")
            .param("username", "share-adjustment-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    /**
     * 取 seed 標的的主鍵。
     *
     * @param symbol 標的代號
     * @return 標的主鍵
     */
    private Long assetId(String symbol) {
        return jdbcClient.sql("SELECT id FROM assets WHERE symbol = :symbol")
            .param("symbol", symbol)
            .query(Long.class)
            .single();
    }

    /**
     * 以 SQL 直接寫入一筆股數調整。
     *
     * @param row 欄位值
     * @return 新紀錄主鍵
     */
    private Long insertAdjustment(Row row) {
        return jdbcClient.sql("""
                INSERT INTO share_adjustments(
                    user_id, asset_id, type, ratio_new, ratio_old, quantity_delta, effective_date, effective_at,
                    idempotency_key, reversal_of_id
                )
                VALUES (:userId, :assetId, :type, :ratioNew, :ratioOld, :quantityDelta, :effectiveDate, :effectiveAt,
                        :idempotencyKey, :reversalOfId)
                RETURNING id
                """)
            .param("userId", row.userId())
            .param("assetId", row.assetId())
            .param("type", row.type())
            .param("ratioNew", row.ratioNew(), Types.INTEGER)
            .param("ratioOld", row.ratioOld(), Types.INTEGER)
            .param("quantityDelta", new BigDecimal(row.quantityDelta()))
            .param("effectiveDate", row.effectiveDate())
            .param("effectiveAt", row.effectiveAt())
            .param("idempotencyKey", row.idempotencyKey())
            .param("reversalOfId", row.reversalOfId(), Types.BIGINT)
            .query(Long.class)
            .single();
    }

    /**
     * 一列股數調整的欄位值；預設除權日 / 生效瞬間與原列相同、冪等鍵隨機、非沖銷。
     *
     * @param userId         擁有者
     * @param assetId        標的主鍵
     * @param type           類型字串（刻意不用 enum，才能寫入白名單外的值）
     * @param ratioNew       比例分子；可為 null
     * @param ratioOld       比例分母；可為 null
     * @param quantityDelta  股數變動（字串，保留 scale）
     * @param effectiveDate  除權日
     * @param effectiveAt    生效瞬間
     * @param idempotencyKey 冪等鍵
     * @param reversalOfId   被沖銷的原列主鍵；一般紀錄為 null
     */
    private record Row(Long userId, Long assetId, String type, Integer ratioNew, Integer ratioOld,
                       String quantityDelta, LocalDate effectiveDate, OffsetDateTime effectiveAt,
                       String idempotencyKey, Long reversalOfId) {

        Row(Long userId, Long assetId, String type, Integer ratioNew, Integer ratioOld, String quantityDelta) {
            this(userId, assetId, type, ratioNew, ratioOld, quantityDelta, EFFECTIVE_DATE, EFFECTIVE_AT,
                UUID.randomUUID().toString(), null);
        }

        Row reversing(Long originalId) {
            return new Row(userId, assetId, type, ratioNew, ratioOld, quantityDelta, effectiveDate, effectiveAt,
                idempotencyKey, originalId);
        }

        Row at(LocalDate date, OffsetDateTime instant) {
            return new Row(userId, assetId, type, ratioNew, ratioOld, quantityDelta, date, instant,
                idempotencyKey, reversalOfId);
        }

        Row withKey(String key) {
            return new Row(userId, assetId, type, ratioNew, ratioOld, quantityDelta, effectiveDate, effectiveAt,
                key, reversalOfId);
        }
    }
}
