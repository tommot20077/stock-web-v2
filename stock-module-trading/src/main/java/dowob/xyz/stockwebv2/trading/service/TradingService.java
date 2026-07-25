package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradingService {
    private static final int MAX_PAGE = 10_000;
    private static final int MONEY_SCALE = 8;

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
        AssetSummary asset = resolveTradeableAsset(request.symbol());
        TradeType type = TradeType.fromApiValue(request.type());
        BigDecimal fee = request.fee() == null ? BigDecimal.ZERO : request.fee();
        OffsetDateTime executedAt = request.executedAt() == null ? OffsetDateTime.now() : request.executedAt();
        Optional<Holding> current = repository.findHoldingForUpdate(userId, asset.id());
        Holding next = switch (type) {
            case BUY -> calculator.applyBuy(current.orElse(null), userId, asset.id(), request.quantity(), request.price(), fee, OffsetDateTime.now());
            case SELL -> calculator.applySell(current.orElse(null), request.quantity(), request.price(), fee, OffsetDateTime.now());
        };
        if (next.id() == null) {
            // 首次建倉（僅 BUY 會走到此；SELL 於空持倉已在 applySell 拋 INSUFFICIENT）。併發下另一交易
            // 可能同時插入同 (user_id, asset_id) 持倉，故以 ON CONFLICT DO NOTHING 嘗試建倉；若被搶先，
            // 重讀（FOR UPDATE 會等對方 commit）後改以 update 併倉，避免唯一鍵衝突拋 500。
            if (repository.insertHoldingIfAbsent(next).isEmpty()) {
                Holding existing = repository.findHoldingForUpdate(userId, asset.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CONFLICT, ErrorCode.TRADE_CONFLICT.defaultMessage()));
                repository.updateHolding(
                    calculator.applyBuy(existing, userId, asset.id(), request.quantity(), request.price(), fee, OffsetDateTime.now())
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
     * 不會抵達 repository，更不可能成為 SQL 文字。</p>
     *
     * @param userId    交易擁有者 id
     * @param symbol    標的代號；null 或空白代表不依標的篩選
     * @param type      交易類型 BUY / SELL，大小寫不敏感；null 或空白代表不篩選
     * @param dateFrom  成交時間下界（含）的 ISO-8601 字串；null 或空白代表不設下界
     * @param dateTo    成交時間上界（不含）的 ISO-8601 字串；null 或空白代表不設上界
     * @param sort      排序鍵 executedAt / total / quantity；null 或空白代表預設 executedAt
     * @param direction 排序方向 asc / desc；null 或空白代表預設 desc
     * @param page      頁碼，超出範圍會被夾限至 0..10000
     * @param size      每頁筆數，超出範圍會被夾限至 1..100
     * @return 該頁交易 DTO 與符合篩選條件的總筆數
     * @throws BusinessException 標的不存在、交易類型不支援、排序參數或日期格式非法時
     */
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
        Long assetId = null;
        if (symbol != null && !symbol.isBlank()) {
            assetId = resolveAsset(symbol).id();
        }
        TradeType tradeType = type == null || type.isBlank() ? null : TradeType.fromApiValue(type);
        OffsetDateTime from = parseTimestamp(dateFrom, "dateFrom");
        OffsetDateTime to = parseTimestamp(dateTo, "dateTo");
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(100, size));
        TradeQuery query = new TradeQuery(
            userId,
            assetId,
            tradeType,
            from,
            to,
            TradeSortKey.fromApiValue(sort),
            SortDirection.fromApiValue(direction),
            safePage,
            safeSize
        );
        PageResponse<TradeTransaction> trades = repository.listTransactions(query);
        return PageResponse.of(trades.items().stream().map(mapper::toTradeDto).toList(), trades.page(), trades.size(), trades.totalElements());
    }

    /**
     * 解析 ISO-8601 時間字串。
     *
     * <p>錯誤訊息只說明期望格式、刻意不回射原始輸入值，避免使用者可控字串被反射回應答
     * （code-standards 錯誤訊息安全規則）。</p>
     *
     * @param value 原始字串；null 或空白代表未指定
     * @param field 欄位名稱，用於組錯誤訊息
     * @return 解析後的時間；未指定時回傳 null
     * @throws BusinessException 格式無法解析時丟出 VALIDATION_FAILED
     */
    private OffsetDateTime parseTimestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " must be an ISO-8601 timestamp");
        }
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
        if (symbol == null || symbol.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String cleanNote(String note) {
        if (note == null || note.isBlank()) {
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
