package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.domain.TradeSortKey;
import dowob.xyz.stockwebv2.trading.domain.TradeType;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 交易清單查詢的型別化查詢物件（D-05 / D-06 / D-07）。
 *
 * <p>service 層負責把 HTTP 傳入的原始字串全部驗證、解析成本物件後才交給 repository；
 * repository 因此只看得到已通過白名單的型別化值，避免簽名膨脹為一長串散裝參數，
 * 也讓「非法值不可能抵達 SQL」成為型別上的保證。</p>
 *
 * @param userId    交易擁有者；恆為 WHERE 條件，確保使用者隔離（ASVS V4）
 * @param assetId   標的資產 id；null 代表不依標的篩選
 * @param type      交易類型；null 代表不依類型篩選
 * @param dateFrom  成交時間下界（含）；null 代表不設下界
 * @param dateTo    成交時間上界（不含，半開區間）；null 代表不設上界
 * @param sortKey   排序鍵白名單值
 * @param direction 排序方向白名單值
 * @param page      頁碼，已由 service 夾限
 * @param size      每頁筆數，已由 service 夾限
 * @author Yuan
 * @version 1.0
 */
public record TradeQuery(
    Long userId,
    Long assetId,
    TradeType type,
    OffsetDateTime dateFrom,
    OffsetDateTime dateTo,
    TradeSortKey sortKey,
    SortDirection direction,
    int page,
    int size
) {
    /**
     * 驗證不可為 null 的欄位；排序鍵與方向在 service 層必定已帶預設值，
     * 若為 null 代表接線錯誤，寧可即早失敗也不要讓 ORDER BY 片段變成 null。
     */
    public TradeQuery {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(direction, "direction");
    }
}
