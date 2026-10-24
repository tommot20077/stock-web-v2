package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.trading.domain.CashEntryView;
import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;

import java.util.Optional;
import java.util.UUID;

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
     * <p>回傳空 Optional 有<strong>兩種</strong>原因，呼叫端須以冪等鍵重讀來區分：同一使用者的同一把冪等鍵
     * 已存在（冪等重送／同 key 併發），或這是一筆沖銷、而原分錄已被另一筆沖銷搶先（併發雙沖銷，
     * 衝突於 {@code uk_cash_ledger_entries_reversal_of}）。重讀命中 → 前者；重讀落空 → 後者。</p>
     *
     * @param entry 待寫入的分錄；{@code id} 與 {@code createdAt} 由資料庫產生
     * @return 實際建立的分錄；任一唯一約束衝突時回傳空 Optional
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

    /**
     * 以對外 UUID 查出該使用者自己的分錄，用來解析沖銷的原分錄。
     *
     * <p>{@code where} 同時綁 {@code user_id}：別人的分錄與不存在的分錄一樣查不到，
     * 呼叫端因此只能回 404，不洩漏他人分錄是否存在（judgment §6、威脅 T-04.1-20）。</p>
     *
     * @param userId 分錄擁有者 id
     * @param uuid   分錄的對外 UUID
     * @return 該使用者的分錄；查無（含屬於他人）時回傳空 Optional
     */
    Optional<CashLedgerEntry> findByUuid(Long userId, UUID uuid);

    /**
     * 判斷某筆分錄是否已有沖銷分錄指向它。
     *
     * <p>這只是應用層的快速檢查，用來回可分派的錯誤碼；併發下的正確性由
     * {@code uk_cash_ledger_entries_reversal_of} 保證，不依賴本方法。</p>
     *
     * @param originalId 原分錄的內部主鍵
     * @return 已被沖銷時為 true
     */
    boolean existsReversalOf(Long originalId);
}
