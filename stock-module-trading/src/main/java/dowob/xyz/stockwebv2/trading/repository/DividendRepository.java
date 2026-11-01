package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.CashDividend;

import java.util.Optional;
import java.util.UUID;

/**
 * 現金股利帳本（{@code cash_dividends}）的資料存取介面。
 *
 * <p>帳本為 append-only：本介面只有寫入與查詢，沒有更新或刪除。所有查詢一律綁 {@code user_id}
 * （以主鍵查的 {@link #isReversed} / {@link #uuidOf} 除外，其主鍵只來自已綁使用者範圍查出的列）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface DividendRepository {

    /**
     * 帶冪等鍵的寫入（insert-first）。
     *
     * <p>回傳空 Optional 有兩種原因，呼叫端須以冪等鍵重讀來區分：同一使用者的同一把冪等鍵已存在
     * （重讀命中 → 冪等），或另一把 key 的沖銷搶先沖銷了同一筆原列（重讀落空）。</p>
     *
     * @param dividend 待寫入的紀錄
     * @return 實際建立的紀錄；任一唯一約束衝突時回傳空 Optional
     */
    Optional<CashDividend> insertIfAbsent(CashDividend dividend);

    /**
     * 以冪等鍵查出該使用者既有的紀錄。
     *
     * @param userId         紀錄擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有紀錄；查無此 key 時回傳空 Optional
     */
    Optional<CashDividend> findByIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * 以對外 UUID 查出該使用者自己的紀錄；他人的紀錄查不到（呼叫端回 404 而不洩漏存在性）。
     *
     * @param userId 紀錄擁有者 id
     * @param uuid   紀錄的對外 UUID
     * @return 該使用者的紀錄；查無（含屬於他人）時回傳空 Optional
     */
    Optional<CashDividend> findByUuid(Long userId, UUID uuid);

    /**
     * 判斷某筆紀錄是否已有沖銷列指向它。
     *
     * @param dividendId 原紀錄的內部主鍵
     * @return 已被沖銷時為 true
     */
    boolean isReversed(Long dividendId);

    /**
     * 取某筆紀錄的對外 UUID（DTO 的 {@code reversalOfId} 由內部主鍵轉成 UUID 時使用）。
     *
     * @param dividendId 紀錄的內部主鍵
     * @return 對外 UUID；查無時回傳空 Optional
     */
    Optional<UUID> uuidOf(Long dividendId);
}
