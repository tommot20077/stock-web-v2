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
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import dowob.xyz.stockwebv2.trading.repository.LatestAssetPrice;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
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
            repository.insertHolding(next);
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

    public PageResponse<TradeDto> listTrades(Long userId, String symbol, int page, int size) {
        Long assetId = null;
        if (symbol != null && !symbol.isBlank()) {
            assetId = resolveAsset(symbol).id();
        }
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(100, size));
        PageResponse<TradeTransaction> trades = repository.listTransactions(userId, assetId, safePage, safeSize);
        return PageResponse.of(trades.items().stream().map(mapper::toTradeDto).toList(), trades.page(), trades.size(), trades.totalElements());
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
            BigDecimal realizedPnl = sum(holdings.stream().map(HoldingDto::realizedPnl).toList());
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
