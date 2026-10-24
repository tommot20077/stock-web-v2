package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;

import java.util.Optional;

/**
 * 現金分錄帳本的資料存取介面。
 *
 * @author Yuan
 * @version 1.0
 */
public interface CashLedgerRepository {

    /**
     * 帶冪等鍵的分錄寫入；同一使用者的同一把冪等鍵只會寫入一列。
     *
     * @param entry 待寫入的分錄；{@code id} 與 {@code createdAt} 由資料庫產生
     * @return 實際建立的分錄；冪等鍵已被同一使用者用過時回傳空 Optional
     */
    Optional<CashLedgerEntry> insertIfAbsent(CashLedgerEntry entry);

    /**
     * 以冪等鍵查出該使用者既有的分錄。
     *
     * @param userId         分錄擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有分錄；查無此 key 時回傳空 Optional
     */
    Optional<CashLedgerEntry> findByIdempotencyKey(Long userId, String idempotencyKey);
}
