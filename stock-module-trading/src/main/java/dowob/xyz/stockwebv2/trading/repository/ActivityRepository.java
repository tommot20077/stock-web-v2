package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.ActivityKind;
import dowob.xyz.stockwebv2.trading.domain.ActivitySortKey;
import dowob.xyz.stockwebv2.trading.domain.ActivityTypeFilter;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 統一活動列表（交易 + 現金股利 + 股數調整）的唯讀查詢（PORT-13，D-12）。
 *
 * <p>三種事件分屬三張表（04.2-02 裁決採新表），「全部」頁籤必須在資料庫端以 UNION ALL 合併後再分頁，
 * 前端合併兩個各自分頁的端點會在跨頁時漏列或重複（PORT-08 修過的錯誤）。</p>
 *
 * <p>{@link #count} 與 {@link #list} 是兩道獨立述句，呼叫端必須在同一個可重複讀的唯讀交易內呼叫，
 * 否則 total 與列表可能來自不同快照。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ActivityRepository {

    /**
     * 查詢符合條件的一頁活動列。
     *
     * @param userId 擁有者 id；三個分支各自以此限定，其他使用者的列永遠不會出現
     * @param query  篩選、排序與分頁條件
     * @return 該頁活動列（依排序鍵、{@code created_at}、{@code uuid} 排序）
     */
    List<ActivityRow> list(Long userId, ActivityQuery query);

    /**
     * 計算符合條件的活動總筆數（忽略分頁）。
     *
     * @param userId 擁有者 id
     * @param query  篩選條件；排序與分頁欄位不影響結果
     * @return 總筆數
     */
    long count(Long userId, ActivityQuery query);

    /**
     * 活動列表的查詢條件；所有使用者輸入在 service 層已解析為型別化的值。
     *
     * @param type          種類篩選；empty 代表三種事件全部
     * @param assetIdOrNull 標的主鍵；null 代表不依標的篩選
     * @param from          事件時間下界（含）；null 代表不設下界
     * @param to            事件時間上界（不含）；null 代表不設上界
     * @param sort          排序鍵
     * @param direction     排序方向
     * @param page          頁碼（已夾限）
     * @param size          每頁筆數（已夾限）
     */
    record ActivityQuery(
        Optional<ActivityTypeFilter> type,
        Long assetIdOrNull,
        OffsetDateTime from,
        OffsetDateTime to,
        ActivitySortKey sort,
        SortDirection direction,
        int page,
        int size
    ) {
        /**
         * 擋下接線錯誤：type / sort / direction 在 service 層必定已帶值或預設值，
         * 為 null 代表呼叫端漏傳，寧可即早失敗也不要讓 ORDER BY 片段變成 null。
         */
        public ActivityQuery {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(sort, "sort");
            Objects.requireNonNull(direction, "direction");
        }
    }

    /**
     * UNION ALL 統一輸出的一列；不適用於該 kind 的欄位為 null（契約〈各 kind 欄位適用表〉）。
     *
     * @param id                      原紀錄 UUID
     * @param kind                    列種類
     * @param assetId                 標的主鍵（symbol / currency 由 service 經 AssetFacade 補上）
     * @param occurredAt              交易成交時間 / 股利入帳時間 / 股數調整生效瞬間
     * @param effectiveDate           僅股數調整：除權日
     * @param createdAt               寫入時間
     * @param quantity                交易數量 / 配股股數 / 拆股 ΔQ；股利為 null
     * @param price                   僅交易
     * @param fee                     僅交易
     * @param amount                  交易為 quantity × price、股利為實領總額；股數調整為 null
     * @param withholdingTax          僅股利
     * @param ratioNew                僅拆股
     * @param ratioOld                僅拆股
     * @param note                    備註
     * @param reversalOfId            沖銷列指向的原列 UUID
     * @param reversalOfOccurredAt    原列的事件時間
     * @param reversalOfEffectiveDate 原列的除權日（僅股數調整）
     * @param reversed                此列是否已被沖銷
     * @param reversible              此列目前是否可沖銷
     */
    record ActivityRow(
        UUID id,
        ActivityKind kind,
        Long assetId,
        OffsetDateTime occurredAt,
        LocalDate effectiveDate,
        OffsetDateTime createdAt,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal amount,
        BigDecimal withholdingTax,
        Integer ratioNew,
        Integer ratioOld,
        String note,
        UUID reversalOfId,
        OffsetDateTime reversalOfOccurredAt,
        LocalDate reversalOfEffectiveDate,
        boolean reversed,
        boolean reversible
    ) {
    }
}
