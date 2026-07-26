package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser.RangeBound;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateTradeRequest;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.api.TradeDto;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingCalculator;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.domain.TradeSortKey;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import dowob.xyz.stockwebv2.trading.repository.LatestAssetPrice;
import dowob.xyz.stockwebv2.trading.repository.TradeQuery;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradingService {
    private static final int MAX_PAGE = 10_000;
    private static final int MONEY_SCALE = 8;

    /**
     * 未帶時區偏移的查詢日期所採用的基準時區。
     *
     * <p>{@code dateFrom=2026-01-01} 這類值本身不含時區資訊，必須補一個基準才能比對
     * {@code timestamptz} 欄位。此處固定為 UTC 並寫入 API 契約，讓同一組參數在任何部署
     * 環境都得到相同結果；需要當地時間語意的客戶端請自行帶完整偏移量。</p>
     */
    private static final ZoneOffset QUERY_DEFAULT_OFFSET = ZoneOffset.UTC;

    /**
     * {@code executedAt} 容許超前伺服器時鐘的幅度。
     *
     * <p>成交時間由提交者填寫，補登舊交易是明確支援的情境，故<strong>不設下界</strong>；
     * 但未來時間沒有合理用途，且會讓該筆交易落在任何「至今為止」的區間查詢之外。
     * 保留數分鐘容忍度是為了吸收客戶端與伺服器之間正常的時鐘偏移，避免誤殺合法請求。</p>
     */
    private static final Duration EXECUTED_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final TradingRepository repository;
    private final AssetFacade assetFacade;
    private final PortfolioCache portfolioCache;
    private final TradingMapper mapper;
    private final HoldingCalculator calculator;

    public TradingService(
        TradingRepository repository,
        AssetFacade assetFacade,
        PortfolioCache portfolioCache,
        TradingMapper mapper
    ) {
        this.repository = repository;
        this.assetFacade = assetFacade;
        this.portfolioCache = portfolioCache;
        this.mapper = mapper;
        this.calculator = new HoldingCalculator();
    }

    @Transactional
    public TradeDto createTrade(Long userId, CreateTradeRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        OffsetDateTime now = OffsetDateTime.now();
        AssetSummary asset = resolveTradeableAsset(request.symbol());
        TradeType type = TradeType.fromApiValue(request.type());
        BigDecimal fee = request.fee() == null ? BigDecimal.ZERO : request.fee();
        OffsetDateTime executedAt = resolveExecutedAt(request.executedAt(), now);
        Optional<Holding> current = repository.findHoldingForUpdate(userId, asset.id());
        Holding next = switch (type) {
            case BUY -> calculator.applyBuy(current.orElse(null), userId, asset.id(), request.quantity(), request.price(), fee, now);
            case SELL -> calculator.applySell(current.orElse(null), request.quantity(), request.price(), fee, now);
        };
        if (next.id() == null) {
            // 首次建倉（僅 BUY 會走到此；SELL 於空持倉已在 applySell 拋 INSUFFICIENT）。併發下另一交易
            // 可能同時插入同 (user_id, asset_id) 持倉，故以 ON CONFLICT DO NOTHING 嘗試建倉；若被搶先，
            // 重讀（FOR UPDATE 會等對方 commit）後改以 update 併倉，避免唯一鍵衝突拋 500。
            if (repository.insertHoldingIfAbsent(next).isEmpty()) {
                Holding existing = repository.findHoldingForUpdate(userId, asset.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CONFLICT, ErrorCode.TRADE_CONFLICT.defaultMessage()));
                repository.updateHolding(
                    calculator.applyBuy(existing, userId, asset.id(), request.quantity(), request.price(), fee, now)
                );
            }
        } else {
            repository.updateHolding(next);
        }
        TradeTransaction saved = repository.insertTransaction(new TradeTransaction(
            null,
            UUID.randomUUID(),
            userId,
            asset.id(),
            asset.symbol(),
            type,
            request.quantity(),
            request.price(),
            fee,
            cleanNote(request.note()),
            executedAt,
            null
        ));
        portfolioCache.invalidateAfterTrade(userId, asset.id());
        return mapper.toTradeDto(saved);
    }

    /**
     * 查詢使用者的交易紀錄，支援標的、交易類型與成交時間區間篩選，以及白名單排序（D-05 / D-06 / D-07）。
     *
     * <p>本方法是 HTTP 原始字串進入資料層前的唯一驗證關卡：所有參數在此解析為型別化的
     * {@link TradeQuery}，任何白名單外的值都以 {@link BusinessException} 中止，
     * 不會抵達 repository，更不可能成為 SQL 文字。驗證順序刻意由「零 I/O 的白名單比對」
     * 排到「需查資料庫的 symbol 解析」，讓必定會被拒絕的請求不必先付一次查詢成本。</p>
     *
     * <p>標記為唯讀交易：repository 內 count 與 list 是兩道獨立述句，若不共用同一個交易
     * 快照，併發寫入落在兩者之間時 {@code totalElements} 會與 {@code items} 漂移，
     * 客戶端算出的 {@code totalPages} 隨之失準。</p>
     *
     * @param userId    交易擁有者 id
     * @param symbol    標的代號；null 或空白代表不依標的篩選
     * @param type      交易類型 BUY / SELL，大小寫不敏感；null 或空白代表不篩選
     * @param dateFrom  成交時間下界（含）；接受的格式見 {@link ApiTimeParser#parseRangeBound}；null 或空白代表不設下界
     * @param dateTo    成交時間上界（不含）；接受的格式見 {@link ApiTimeParser#parseRangeBound}；null 或空白代表不設上界
     * @param sort      排序鍵 executedAt / createdAt / total / quantity；null 或空白代表預設 executedAt
     * @param direction 排序方向 asc / desc；null 或空白代表預設 desc
     * @param page      頁碼，超出範圍會被夾限至 0..10000
     * @param size      每頁筆數，超出範圍會被夾限至 1..100
     * @return 該頁交易 DTO 與符合篩選條件的總筆數
     * @throws BusinessException 標的不存在、交易類型不支援、排序參數非法、日期格式非法，
     *                           或 dateFrom 晚於 dateTo 時
     */
    @Transactional(readOnly = true)
    public PageResponse<TradeDto> listTrades(
        Long userId,
        String symbol,
        String type,
        String dateFrom,
        String dateTo,
        String sort,
        String direction,
        int page,
        int size
    ) {
        TradeType tradeType = TradeType.fromFilterValue(type);
        TradeSortKey sortKey = TradeSortKey.fromApiValue(sort);
        SortDirection sortDirection = SortDirection.fromApiValue(direction);
        OffsetDateTime from = ApiTimeParser.parseRangeBound(dateFrom, "dateFrom", RangeBound.LOWER, QUERY_DEFAULT_OFFSET);
        OffsetDateTime to = ApiTimeParser.parseRangeBound(dateTo, "dateTo", RangeBound.UPPER, QUERY_DEFAULT_OFFSET);
        if (from != null && to != null && from.isAfter(to)) {
            // 顛倒的區間會產生恆為空的 WHERE，靜默回傳空頁會讓「日期選反」看起來像「資料不見了」。
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "dateFrom must not be after dateTo");
        }
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(100, size));
        Long assetId = StringUtils.isBlank(symbol) ? null : resolveAsset(symbol).id();
        TradeQuery query = new TradeQuery(
            userId,
            assetId,
            tradeType,
            from,
            to,
            sortKey,
            sortDirection,
            safePage,
            safeSize
        );
        PageResponse<TradeTransaction> trades = repository.listTransactions(query);
        return PageResponse.of(trades.items().stream().map(mapper::toTradeDto).toList(), trades.page(), trades.size(), trades.totalElements());
    }

    /**
     * 決定交易的成交時間，並擋下明顯超前伺服器時鐘的值。
     *
     * <p>刻意不設下界：補登舊交易是本帳本明確支援的情境。上界則保留
     * {@link #EXECUTED_AT_FUTURE_TOLERANCE} 的時鐘偏移容忍度。</p>
     *
     * @param requested 請求帶入的成交時間；null 代表以現在時間入帳
     * @param now       本次交易的基準時間
     * @return 實際採用的成交時間
     * @throws BusinessException 成交時間超前基準時間逾容忍度時丟出 VALIDATION_FAILED
     */
    private OffsetDateTime resolveExecutedAt(OffsetDateTime requested, OffsetDateTime now) {
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now.plus(EXECUTED_AT_FUTURE_TOLERANCE))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "executedAt must not be in the future");
        }
        return requested;
    }

    public List<HoldingDto> listHoldings(Long userId) {
        return repository.listHoldings(userId).stream()
            .map(position -> portfolioCache.readHolding(userId, position.assetId()).orElseGet(() -> calculateAndCacheHolding(position)))
            .toList();
    }

    public PortfolioSummaryDto summary(Long userId) {
        return portfolioCache.readSummary(userId).orElseGet(() -> {
            List<HoldingDto> holdings = listHoldings(userId);
            BigDecimal marketValue = sum(holdings.stream().map(HoldingDto::marketValue).toList());
            BigDecimal costBasis = sum(holdings.stream().map(HoldingDto::costBasis).toList());
            // realized PnL 需涵蓋已平倉部位（total_quantity = 0，不在 listHoldings 內），故直接由
            // repository 加總所有持倉的 realized_pnl，避免平倉後遺失已實現損益。
            BigDecimal realizedPnl = repository.sumRealizedPnl(userId);
            BigDecimal unrealizedPnl = sum(holdings.stream().map(HoldingDto::unrealizedPnl).toList());
            BigDecimal totalPnl = realizedPnl.add(unrealizedPnl);
            PortfolioSummaryDto dto = new PortfolioSummaryDto(
                marketValue,
                costBasis,
                realizedPnl,
                unrealizedPnl,
                totalPnl,
                roi(totalPnl, costBasis),
                holdings.size()
            );
            portfolioCache.writeSummary(userId, dto);
            return dto;
        });
    }

    private HoldingDto calculateAndCacheHolding(HoldingPosition position) {
        LatestAssetPrice latest = repository.findLatestPrice(position.assetId())
            .orElse(new LatestAssetPrice(position.avgCost(), position.lastUpdated()));
        BigDecimal costBasis = position.totalQuantity().multiply(position.avgCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal marketValue = position.totalQuantity().multiply(latest.price()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unrealized = marketValue.subtract(costBasis);
        HoldingDto dto = new HoldingDto(
            position.assetUuid().toString(),
            position.symbol(),
            position.assetName(),
            position.totalQuantity(),
            position.avgCost(),
            costBasis,
            latest.price(),
            marketValue,
            position.realizedPnl(),
            unrealized,
            roi(unrealized, costBasis),
            latest.priceTime(),
            position.lastUpdated()
        );
        portfolioCache.writeHolding(position.userId(), position.assetId(), dto);
        return dto;
    }

    private AssetSummary resolveTradeableAsset(String symbol) {
        AssetSummary asset = resolveAsset(symbol);
        if (!asset.active() || !asset.tradeable()) {
            throw new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset is not tradeable: " + symbol);
        }
        return asset;
    }

    private AssetSummary resolveAsset(String symbol) {
        String normalized = normalizeSymbol(symbol);
        return assetFacade.findBySymbol(normalized)
            .orElseThrow(() -> new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found: " + normalized));
    }

    private String normalizeSymbol(String symbol) {
        if (StringUtils.isBlank(symbol)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String cleanNote(String note) {
        if (StringUtils.isBlank(note)) {
            return null;
        }
        return note.trim();
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal roi(BigDecimal pnl, BigDecimal costBasis) {
        if (costBasis == null || costBasis.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return pnl.divide(costBasis, MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
