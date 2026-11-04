package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentEntry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 股數調整帳本（{@code share_adjustments}）的資料存取介面（ADJ-01）。
 *
 * <p>帳本為 append-only：本介面只有寫入與查詢，沒有更新或刪除。所有查詢一律綁 {@code user_id}
 * （以主鍵查的 {@link #isReversed} / {@link #uuidOf} 除外，其主鍵只來自已綁使用者範圍查出的列）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
public interface ShareAdjustmentRepository {

    /**
     * 帶冪等鍵的寫入（insert-first）。
     *
     * <p>回傳空 Optional 有兩種原因，呼叫端須以冪等鍵重讀來區分：同一使用者的同一把冪等鍵已存在
     * （重讀命中 → 冪等），或另一把 key 的沖銷搶先沖銷了同一筆原列（重讀落空）。</p>
     *
     * @param entry 待寫入的紀錄
     * @return 實際建立的紀錄；任一唯一約束衝突時回傳空 Optional
     */
    Optional<ShareAdjustmentEntry> insertIfAbsent(ShareAdjustmentEntry entry);

    /**
     * 以冪等鍵查出該使用者既有的紀錄。
     *
     * @param userId         紀錄擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有紀錄；查無此 key 時回傳空 Optional
     */
    Optional<ShareAdjustmentEntry> findByIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * 以對外 UUID 查出該使用者自己的紀錄；他人的紀錄查不到。
     *
     * @param userId 紀錄擁有者 id
     * @param uuid   紀錄的對外 UUID
     * @return 該使用者的紀錄；查無（含屬於他人）時回傳空 Optional
     */
    Optional<ShareAdjustmentEntry> findByUuid(Long userId, UUID uuid);

    /**
     * 判斷某筆紀錄是否已有沖銷列指向它。
     *
     * @param adjustmentId 原紀錄的內部主鍵
     * @return 已被沖銷時為 true
     */
    boolean isReversed(Long adjustmentId);

    /**
     * 取某筆紀錄的對外 UUID（DTO 的 {@code reversalOfId} 由內部主鍵轉成 UUID 時使用）。
     *
     * @param adjustmentId 紀錄的內部主鍵
     * @return 對外 UUID；查無時回傳空 Optional
     */
    Optional<UUID> uuidOf(Long adjustmentId);

    /**
     * 新調整在其生效瞬間所依據的持股數（RESEARCH Q2.2 的 {@code Q_asOf}）。
     *
     * <p>= 交易帳本中 {@code executed_at < effectiveAt} 的買入減賣出，加上 {@code effective_at <= effectiveAt} 的
     * 既有股數調整（沖銷列反號）。持倉表只有「目前」值，事件日持股只能由帳本推得。</p>
     *
     * <p>兩段切點刻意不同（Yuan 2026-11-04 裁決「同日依記錄順序疊加」）：同一除權日可記多筆調整，後記錄者要以
     * 「含先記錄者」的持股換算（100 股同日兩次 2:1 → 400 股，而不是 300）；呼叫時本筆尚未寫入，
     * 所以 {@code <=} 只會納入同一瞬間<strong>已先記錄</strong>的調整（與 {@link #findLatestActive} 的記錄順序一致）。
     * 交易維持嚴格小於：除權日起點（含）之後的成交已是除權後股數，不參與換算。</p>
     *
     * @param userId      擁有者
     * @param assetId     標的主鍵
     * @param effectiveAt 新調整的生效瞬間（除權日的交易日起點）
     * @return 生效瞬間的換算基準持股；從未持有時為 0
     */
    BigDecimal quantityAsOf(Long userId, Long assetId, OffsetDateTime effectiveAt);

    /**
     * 該標的最晚一筆「非沖銷列且未被沖銷」的調整（依 {@code effective_at DESC, id DESC}）。
     *
     * @param userId  擁有者
     * @param assetId 標的主鍵
     * @return 最晚的有效調整；沒有時回傳空 Optional
     */
    Optional<ShareAdjustmentEntry> findLatestActive(Long userId, Long assetId);

    /**
     * 列出該使用者在某瞬間（含）之後生效的所有股數調整，供今日損益扣除當日非現金股數變動（ADJ-02 / D-10）。
     *
     * <p>沖銷列以<strong>反號</strong>回傳，所以同日記錄又沖銷的淨額為 0；同一除權日的多筆調整全部列出
     * （依記錄順序疊加，加總即為當日淨變動）。呼叫端以今日成交查詢的同一個 {@code since} 一次取回，
     * 再依各資產自己的交易日起點過濾。</p>
     *
     * @param userId 擁有者（只讀本人的列）
     * @param since  生效瞬間下界（含）
     * @return 生效時間不早於 {@code since} 的調整，依生效時間與記錄順序排序；沒有時為空 list
     */
    List<DayAdjustment> listSince(Long userId, OffsetDateTime since);

    /**
     * 今日損益用的一筆股數調整。
     *
     * @param assetId     標的主鍵
     * @param signedDelta 帶號股數變動（沖銷列已反號）
     * @param effectiveAt 生效瞬間（除權日的交易日起點）
     */
    record DayAdjustment(Long assetId, BigDecimal signedDelta, OffsetDateTime effectiveAt) {
    }
}
