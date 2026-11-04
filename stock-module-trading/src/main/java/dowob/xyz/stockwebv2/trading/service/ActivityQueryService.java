package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser.RangeBound;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.PortfolioActivityDto;
import dowob.xyz.stockwebv2.trading.domain.ActivitySortKey;
import dowob.xyz.stockwebv2.trading.domain.ActivityTypeFilter;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityQuery;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityRow;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
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
     * @throws FieldValidationException type / sort / direction 不在白名單內時（fields 指名該參數）
     * @throws BusinessException        日期格式非法、dateFrom 晚於 dateTo（{@code VALIDATION_FAILED}），
     *                                  或標的不存在（{@code ASSET_NOT_FOUND}，fields.symbol）時
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
        /* 驗證順序同 GET /trades：零 I/O 的白名單比對與日期解析先行，需查資料庫的 symbol 解析最後。 */
        Optional<ActivityTypeFilter> typeFilter = ActivityTypeFilter.fromApiValue(type);
        ActivitySortKey sortKey = ActivitySortKey.fromApiValue(sort);
        SortDirection sortDirection = parseDirection(direction);
        OffsetDateTime from = ApiTimeParser.parseRangeBound(dateFrom, "dateFrom", RangeBound.LOWER);
        OffsetDateTime to = ApiTimeParser.parseRangeBound(dateTo, "dateTo", RangeBound.UPPER);
        if (from != null && to != null && from.isAfter(to)) {
            /* 顛倒的區間會產生恆為空的 WHERE，靜默回傳空頁會讓「日期選反」看起來像「資料不見了」（同 GET /trades）。 */
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "dateFrom must not be after dateTo");
        }
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Long assetId = StringUtils.isBlank(symbol) ? null : resolveAssetId(symbol);
        ActivityQuery query = new ActivityQuery(
            typeFilter, assetId, from, to, sortKey, sortDirection, safePage, safeSize);
        long total = repository.count(userId, query);
        List<ActivityRow> rows = repository.list(userId, query);
        Map<Long, AssetProfile> profiles = profilesOf(rows);
        return PageResponse.of(
            rows.stream().map(row -> toDto(row, profiles.get(row.assetId()))).toList(), safePage, safeSize, total);
    }

    /**
     * 解析排序方向；白名單與 {@code GET /trades} 相同（{@link SortDirection}），錯誤另附 {@code fields.direction}。
     *
     * @param direction 使用者傳入的原始字串；null 或空白代表 desc
     * @return 排序方向
     * @throws FieldValidationException 值不在白名單內時；訊息不回射原始輸入
     */
    private static SortDirection parseDirection(String direction) {
        try {
            return SortDirection.fromApiValue(direction);
        } catch (BusinessException invalid) {
            throw new FieldValidationException("direction must be asc or desc", Map.of("direction", "must be asc or desc"));
        }
    }

    /**
     * 把標的代號解析為資產主鍵；語意與 {@code GET /trades} 相同（trim、大寫、查無 → 404 {@code fields.symbol}）。
     *
     * @param symbol 非空白的標的代號
     * @return 資產主鍵
     * @throws BusinessException 查無標的時丟出 {@link ErrorCode#ASSET_NOT_FOUND}
     */
    private Long resolveAssetId(String symbol) {
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return assetFacade.findBySymbol(normalized)
            .map(AssetSummary::id)
            .orElseThrow(() -> new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found",
                Map.of("symbol", "not found")));
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
