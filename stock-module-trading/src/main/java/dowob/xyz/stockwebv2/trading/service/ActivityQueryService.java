package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.trading.api.PortfolioActivityDto;
import dowob.xyz.stockwebv2.trading.domain.ActivitySortKey;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityQuery;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 統一活動列表（{@code GET /api/v1/portfolio/activities}，PORT-13）的唯讀查詢服務。
 *
 * <p>交易、現金股利、股數調整分屬三張表，由 {@link ActivityRepository} 在資料庫端以 UNION ALL 合併後分頁
 * （D-12）；本服務負責參數解析、在同一快照內取 total 與列表，並以<strong>一次</strong>
 * {@link AssetFacade#findProfiles} 補上 symbol / currency（trading 不 JOIN 資產主檔，D-21）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class ActivityQueryService {

    private static final Logger log = LoggerFactory.getLogger(ActivityQueryService.class);

    /**
     * 頁碼上限，與 {@code GET /trades} 相同。
     */
    private static final int MAX_PAGE = 10_000;

    /**
     * 每頁筆數上限，與 {@code GET /trades} 相同。
     */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 活動列表的 UNION ALL 查詢。
     */
    private final ActivityRepository repository;

    /**
     * 取得標的代號與交易幣別（trading 不 JOIN 資產主檔，D-21）。
     */
    private final AssetFacade assetFacade;

    public ActivityQueryService(ActivityRepository repository, AssetFacade assetFacade) {
        this.repository = repository;
        this.assetFacade = assetFacade;
    }

    /**
     * 查詢登入者的統一活動列表。
     *
     * <p>標記為<strong>可重複讀</strong>的唯讀交易：count 與 list 是兩道獨立述句，PostgreSQL 預設的
     * READ COMMITTED 下兩者各取自己的快照，併發寫入落在兩者之間時 total 會與列表漂移；
     * {@code readOnly = true} 本身不改變隔離層級，必須明確指定（同 {@code CashLedgerService#listEntries}）。</p>
     *
     * @param userId    擁有者 id，一律由 controller 自已驗證的身分取得；其他使用者的紀錄永遠不會出現
     * @param type      種類篩選 BUY / SELL / DIVIDEND / SHARE_ADJUSTMENT；null 或空白代表全部
     * @param symbol    標的代號；null 或空白代表不依標的篩選
     * @param dateFrom  事件時間下界（含），格式同 {@code GET /trades}；null 或空白代表不設下界
     * @param dateTo    事件時間上界（不含），格式同 {@code GET /trades}；null 或空白代表不設上界
     * @param sort      排序鍵 executedAt / createdAt / total / quantity；null 或空白代表 executedAt
     * @param direction 排序方向 asc / desc；null 或空白代表 desc
     * @param page      頁碼，超出範圍會被夾限至 0..10000
     * @param size      每頁筆數，超出範圍會被夾限至 1..100
     * @return 該頁活動 DTO 與符合條件的總筆數
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<PortfolioActivityDto> list(
        Long userId,
        String type,
        String symbol,
        String dateFrom,
        String dateTo,
        String sort,
        String direction,
        int page,
        int size
    ) {
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        ActivityQuery query = new ActivityQuery(
            Optional.empty(), null, null, null, ActivitySortKey.EXECUTED_AT, SortDirection.DESC, safePage, safeSize);
        long total = repository.count(userId, query);
        List<ActivityRow> rows = repository.list(userId, query);
        Map<Long, AssetProfile> profiles = profilesOf(rows);
        return PageResponse.of(
            rows.stream().map(row -> toDto(row, profiles.get(row.assetId()))).toList(), safePage, safeSize, total);
    }

    /**
     * 以一次 {@link AssetFacade#findProfiles} 批次取得本頁所有標的的屬性；空頁不呼叫 Facade。
     *
     * @param rows 本頁活動列
     * @return 資產主鍵到屬性的 map；查無者不在其中
     */
    private Map<Long, AssetProfile> profilesOf(List<ActivityRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> assetIds = rows.stream().map(ActivityRow::assetId).distinct().toList();
        return assetFacade.findProfiles(assetIds);
    }

    /**
     * 把一列活動對應成 DTO；資產屬性缺失（理論上不會發生）時 symbol / currency 為 null 並記 WARN。
     *
     * @param row     活動列
     * @param profile 該列標的的屬性；可為 null
     * @return 對外 DTO
     */
    private PortfolioActivityDto toDto(ActivityRow row, AssetProfile profile) {
        if (profile == null) {
            log.warn("Asset profile is missing for an activity row: assetId={}", row.assetId());
        }
        return new PortfolioActivityDto(
            row.id().toString(),
            row.kind().name(),
            profile == null ? null : profile.symbol(),
            profile == null || profile.currency() == null ? null : profile.currency().name(),
            row.occurredAt(),
            row.effectiveDate(),
            row.createdAt(),
            row.quantity(),
            row.price(),
            row.fee(),
            row.amount(),
            row.withholdingTax(),
            row.ratioNew(),
            row.ratioOld(),
            row.note(),
            Objects.toString(row.reversalOfId(), null),
            row.reversalOfOccurredAt(),
            row.reversalOfEffectiveDate(),
            row.reversed(),
            row.reversible()
        );
    }
}
