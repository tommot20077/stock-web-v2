package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.trading.domain.CashEntryView;
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

    /**
     * 取回該使用者的一頁分錄，排序固定為發生時間新到舊、同時間以 id 新到舊。
     *
     * <p>count 與 list 是兩道獨立述句，必須在<strong>同一個交易</strong>中執行才能共用快照，
     * 否則併發寫入落在兩者之間時 {@code totalElements} 會與 {@code items} 漂移；
     * 呼叫端須以唯讀交易包住。</p>
     *
     * @param userId 分錄擁有者 id；其他使用者的分錄永遠不會出現
     * @param page   頁碼，從 0 起，已由呼叫端夾限
     * @param size   每頁筆數，已由呼叫端夾限
     * @return 該頁分錄視圖與該使用者的分錄總數
     */
    PageResponse<CashEntryView> listEntries(Long userId, int page, int size);
}
