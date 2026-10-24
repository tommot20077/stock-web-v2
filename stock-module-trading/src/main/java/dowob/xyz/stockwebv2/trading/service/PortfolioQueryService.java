package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRateFacade;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRates;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.infrastructure.user.UserPreferenceFacade;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.domain.DayFill;
import dowob.xyz.stockwebv2.trading.domain.DayPnl;
import dowob.xyz.stockwebv2.trading.domain.DayPnlCalculator;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.HoldingSnapshot;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 投資組合的讀時計算：持倉估值與組合摘要。
 *
 * <p>本類別自 {@link TradingService} 原樣抽出，讀取路徑（持倉快取 MGET、對未命中者批次取價、
 * 摘要快取）與交易寫入路徑（冪等鍵、持倉改寫、{@code afterCommit} 快取失效）分屬不同類別，
 * 讓兩條線往後各自演進而不改同一個檔案。寫入端仍由 {@link TradingService#createTrade} 在交易提交後
 * 透過 {@link PortfolioCache#invalidateAfterTrade} 使快取失效，本類別只負責讀取與回填。</p>
 *
 * <p>D-21 / H-1 的原則照舊：持倉與摘要<strong>不 join {@code assets}</strong>（trading 內既有的
 * 三處 {@code join assets} 只為取 uuid / symbol / name，不再增加）。標的的幣別等屬性經
 * {@link AssetFacade#findProfiles} 批次取得，最新價一律經 {@link MarketDataFacade} 取得。</p>
 *
 * <p>D-05 起 summary 的金額欄位以使用者基準幣表示：持倉市值與成本、<strong>逐資產</strong>的已實現損益
 * （含已平倉）依各自幣別經 {@link FxRates} 折算後才加總；換不到匯率的持倉不計入總額，改以
 * {@code fxExcludedCount} 與 {@code unconvertedCurrencies} 回報（D-04）。summary 快取鍵含基準幣，
 * 切換基準幣後立即 miss。</p>
 *
 * <p>D-12 起持倉帶單檔今日價格損益：取價改經 {@link MarketDataFacade#findDailyQuotes}（最新價與前收一次取回，
 * 取代原本只取最新價的查詢，不是多一次呼叫），今日成交以 {@link TradingRepository#listFillsSince} 單次查詢，
 * 由 {@link DayPnlCalculator} 以恆等式計算，不需要日級快照表。</p>
 *
 * <p>快取未命中時每個 Facade 至多一次批次呼叫（共 4 次，D-23），見 {@link #summary} 內的呼叫點註解。</p>
 *
 * @author Yuan
 * @version 3.0
 */
@Service
public class PortfolioQueryService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioQueryService.class);
    private static final int MONEY_SCALE = 8;

    private final TradingRepository repository;
    private final MarketDataFacade marketDataFacade;
    private final PortfolioCache portfolioCache;
    private final UserPreferenceFacade userPreferenceFacade;
    private final AssetFacade assetFacade;
    private final FxRateFacade fxRateFacade;

    /**
     * 建立讀時計算服務。
     *
     * @param repository       交易與持倉資料存取
     * @param marketDataFacade 跨模組最新價查詢
     * @param portfolioCache   持倉與摘要的 Redis 快取
     * @param userPreferenceFacade 跨模組取得使用者基準幣
     * @param assetFacade      跨模組批次取得資產屬性（幣別等）
     * @param fxRateFacade     跨模組取得目前匯率
     */
    public PortfolioQueryService(
        TradingRepository repository,
        MarketDataFacade marketDataFacade,
        PortfolioCache portfolioCache,
        UserPreferenceFacade userPreferenceFacade,
        AssetFacade assetFacade,
        FxRateFacade fxRateFacade
    ) {
        this.repository = repository;
        this.marketDataFacade = marketDataFacade;
        this.portfolioCache = portfolioCache;
        this.userPreferenceFacade = userPreferenceFacade;
        this.assetFacade = assetFacade;
        this.fxRateFacade = fxRateFacade;
    }

    /**
     * 列出使用者的持倉與估值。
     *
     * <p>金額欄位為原幣；每列另帶 {@code currency}、{@code assetType}、{@code sector}，以及依使用者基準幣
     * 折算的 {@code baseCurrency} 與 {@code marketValueInBase}（換不到匯率時為 null），供前端以
     * {@code marketValueInBase / summary.totalMarketValue} 計算權重。持倉快取只存原幣估值，
     * 基準幣欄位於回傳時才補上，所以使用者切換基準幣不需要等快取過期。
     *
     * <p>以固定次數的往返完成：一次批次讀持倉快取、對未命中者一次批次取資產屬性與最新價，再逐筆寫回快取。
     * 原本每筆持倉各自讀快取、取價、寫快取，N 筆約 3N 次往返，成交後三個頁面同時 refetch 還會放大
     * （性能審查 MED-5）。回傳順序與 {@code repository.listHoldings} 相同。
     *
     * @param userId 使用者 id
     * @return 持倉 DTO 列表
     */
    public List<HoldingDto> listHoldings(Long userId) {
        List<HoldingPosition> positions = repository.listHoldings(userId);
        if (positions.isEmpty()) {
            return List.of();
        }
        // Facade 呼叫點（D-23：快取未命中時每個 Facade 至多一次批次呼叫）：
        // 1. userPreferenceFacade.findBaseCurrency — 取基準幣，只用來補 marketValueInBase，不進持倉快取。
        // 2. assetFacade.findProfiles、3. marketDataFacade.findDailyQuotes — 皆只為持倉快取未命中者批次呼叫
        //    （見 valuePositions；findDailyQuotes 同時帶回最新價與前收，取代原本只取最新價的查詢）；
        //    全部命中時兩者皆不呼叫。今日成交 repository.listFillsSince 是本模組自己的表，不算 Facade。
        // 4. fxRateFacade.currentRates — 一次取回完整匯率表，逐列折算在記憶體完成。
        CurrencyCode base = userPreferenceFacade.findBaseCurrency(userId);
        List<HoldingDto> original = valuePositions(userId, positions, null);
        FxRates rates = fxRateFacade.currentRates();
        return original.stream().map(holding -> withBaseValuation(holding, base, rates)).toList();
    }

    /**
     * 為一組持倉位置估值（原幣）：批次讀持倉快取，對未命中者一次批次取資產屬性與最新價並寫回快取。
     *
     * @param userId        使用者 id
     * @param positions     持倉位置
     * @param knownProfiles 呼叫端已批次取得的資產屬性；null 表示尚未取得，未命中時才由本方法一次批次取得
     * @return 與 {@code positions} 同順序的原幣估值 DTO（{@code baseCurrency} / {@code marketValueInBase} 為 null）
     */
    private List<HoldingDto> valuePositions(
        Long userId,
        List<HoldingPosition> positions,
        Map<Long, AssetProfile> knownProfiles
    ) {
        if (positions.isEmpty()) {
            return List.of();
        }
        List<Long> assetIds = positions.stream().map(HoldingPosition::assetId).toList();
        Map<Long, HoldingDto> cached = portfolioCache.readHoldings(userId, assetIds);
        List<Long> misses = assetIds.stream().filter(id -> !cached.containsKey(id)).toList();
        Map<Long, AssetProfile> profiles = Map.of();
        DayContext day = DayContext.EMPTY;
        if (!misses.isEmpty()) {
            profiles = knownProfiles != null ? knownProfiles : assetFacade.findProfiles(misses);
            day = loadDayContext(userId, misses, profiles);
        }
        Map<Long, AssetProfile> missProfiles = profiles;
        DayContext missDay = day;
        return positions.stream()
            .map(position -> {
                HoldingDto hit = cached.get(position.assetId());
                return hit != null
                    ? hit
                    : calculateAndCacheHolding(
                        position,
                        missDay.quotes().get(position.assetId()),
                        missProfiles.get(position.assetId()),
                        missDay.fillsOf(position.assetId()));
            })
            .toList();
    }

    /**
     * 批次取得一組資產的報價與今日成交：一次 {@code findDailyQuotes}、至多一次 {@code listFillsSince}。
     *
     * <p>交易日規則由資產屬性建立（{@code TradingDay.of(assetType, market)}）；資產屬性缺失時退回 UTC，
     * 讓行情仍可取得。各資產的交易日起點以<strong>與 market-data 相同的公式</strong>
     * {@code tradingDay.dayStart(quote.priceTime().toInstant())} 計算，兩邊才會逐位元一致。
     * 今日成交以所有交易日起點的最小值一次查詢，再逐資產以各自的起點過濾。
     *
     * @param userId   使用者 id
     * @param assetIds 要取價的資產 id
     * @param profiles 已取得的資產屬性
     * @return 報價與各資產今日成交；沒有行情的資產不在 {@code quotes} 內
     */
    private DayContext loadDayContext(Long userId, Collection<Long> assetIds, Map<Long, AssetProfile> profiles) {
        Map<Long, TradingDay> tradingDays = new LinkedHashMap<>();
        for (Long assetId : assetIds) {
            AssetProfile profile = profiles.get(assetId);
            tradingDays.put(assetId, profile == null ? TradingDay.UTC : TradingDay.of(profile.assetType(), profile.market()));
        }
        Map<Long, DailyQuote> quotes = marketDataFacade.findDailyQuotes(tradingDays);
        Map<Long, Instant> dayStarts = new HashMap<>();
        quotes.forEach((assetId, quote) -> dayStarts.put(
            assetId, tradingDays.getOrDefault(assetId, TradingDay.UTC).dayStart(quote.priceTime().toInstant())));
        if (dayStarts.isEmpty()) {
            return new DayContext(quotes, Map.of());
        }
        Instant earliest = dayStarts.values().stream().min(Instant::compareTo).orElseThrow();
        Map<Long, List<DayFill>> fillsByAsset = new HashMap<>();
        for (DayFill fill : repository.listFillsSince(userId, OffsetDateTime.ofInstant(earliest, ZoneOffset.UTC))) {
            Instant dayStart = dayStarts.get(fill.assetId());
            if (dayStart != null && !fill.executedAt().toInstant().isBefore(dayStart)) {
                fillsByAsset.computeIfAbsent(fill.assetId(), id -> new ArrayList<>()).add(fill);
            }
        }
        return new DayContext(quotes, fillsByAsset);
    }

    /**
     * 一次請求內的報價與今日成交。
     *
     * @param quotes       資產 id → 目前交易日報價；沒有行情的資產不在內
     * @param fillsByAsset 資產 id → 該資產在其交易日起點之後的成交
     */
    private record DayContext(Map<Long, DailyQuote> quotes, Map<Long, List<DayFill>> fillsByAsset) {
        static final DayContext EMPTY = new DayContext(Map.of(), Map.of());

        List<DayFill> fillsOf(Long assetId) {
            return fillsByAsset.getOrDefault(assetId, List.of());
        }
    }

    /**
     * 依本次基準幣與匯率補上 {@code baseCurrency} / {@code marketValueInBase}；不修改傳入的（可能來自快取的）DTO。
     *
     * @param holding 原幣持倉 DTO
     * @param base    使用者基準幣
     * @param rates   目前匯率
     * @return 補上基準幣市值的新 DTO；缺幣別或換不到匯率時 {@code marketValueInBase} 為 null
     */
    private HoldingDto withBaseValuation(HoldingDto holding, CurrencyCode base, FxRates rates) {
        BigDecimal marketValueInBase = parseCurrency(holding.currency())
            .flatMap(currency -> rates.convert(holding.marketValue(), currency, base))
            .orElse(null);
        return holding.withBaseValuation(base.name(), marketValueInBase);
    }

    private Optional<CurrencyCode> parseCurrency(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CurrencyCode.valueOf(code));
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown currency code in cached holding: {}", code);
            return Optional.empty();
        }
    }

    /**
     * 取得使用者的投資組合摘要（金額以使用者基準幣表示）；優先讀該基準幣的摘要快取，
     * 未命中才由持倉估值與逐資產已實現損益折算重算並回填。
     *
     * <p>換不到匯率的持倉不計入總額，改回報於 {@code fxExcludedCount} 與
     * {@code unconvertedCurrencies}（D-04）；已實現損益逐資產折算，換不到者同樣列入
     * {@code unconvertedCurrencies}。{@code roi} 為 {@code totalPnl / totalCostBasis}，兩者同為基準幣。
     *
     * @param userId 使用者 id
     * @return 投資組合摘要 DTO
     */
    @Transactional(readOnly = true)
    public PortfolioSummaryDto summary(Long userId) {
        // Facade 呼叫點（D-23：快取未命中時 4 次批次呼叫，皆為批次、結果有快取、命中路徑只有第 1 次）：
        // 1. userPreferenceFacade.findBaseCurrency — 取基準幣，同時是 summary 快取鍵的一部分，必須先於快取讀取。
        // 2. assetFacade.findProfiles — 一次取全部持倉（含已平倉）的幣別，供市值與已實現損益折算。
        // 3. marketDataFacade.findDailyQuotes — 只為持倉快取未命中者一次批次取最新價與前收（見 valuePositions）。
        // 4. fxRateFacade.currentRates — 一次取回完整匯率表，之後逐筆折算在記憶體完成。
        // 四者分屬 user / asset / market-data / asset(fx) 不同資料來源，無法合併成同一次往返。
        CurrencyCode base = userPreferenceFacade.findBaseCurrency(userId);
        Optional<PortfolioSummaryDto> cached = portfolioCache.readSummary(userId, base);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<HoldingSnapshot> snapshots = repository.listHoldingSnapshots(userId);
        List<HoldingPosition> positions = repository.listHoldings(userId);
        Set<Long> assetIds = new LinkedHashSet<>();
        snapshots.forEach(snapshot -> assetIds.add(snapshot.assetId()));
        positions.forEach(position -> assetIds.add(position.assetId()));
        Map<Long, AssetProfile> profiles = assetIds.isEmpty() ? Map.of() : assetFacade.findProfiles(assetIds);
        List<HoldingDto> holdings = valuePositions(userId, positions, profiles);
        FxRates rates = fxRateFacade.currentRates();

        Set<String> unconverted = new TreeSet<>();
        int fxExcludedCount = 0;
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        for (int i = 0; i < positions.size(); i++) {
            HoldingDto holding = holdings.get(i);
            AssetProfile profile = profiles.get(positions.get(i).assetId());
            if (profile == null) {
                log.warn("Holding profile missing for assetId={}, excluded from summary", positions.get(i).assetId());
                fxExcludedCount++;
                continue;
            }
            Optional<BigDecimal> marketValueInBase = rates.convert(holding.marketValue(), profile.currency(), base);
            Optional<BigDecimal> costBasisInBase = rates.convert(holding.costBasis(), profile.currency(), base);
            if (marketValueInBase.isEmpty() || costBasisInBase.isEmpty()) {
                fxExcludedCount++;
                unconverted.add(profile.currency().name());
                continue;
            }
            marketValue = marketValue.add(marketValueInBase.get());
            costBasis = costBasis.add(costBasisInBase.get());
        }
        // realized PnL 需涵蓋已平倉部位（total_quantity = 0，不在 listHoldings 內），且必須逐資產依各自幣別折算，
        // 不能像舊版以 SQL 跨幣別 sum。已實現損益為 0 的資產不需要匯率，不計入 unconverted。
        BigDecimal realizedPnl = BigDecimal.ZERO;
        for (HoldingSnapshot snapshot : snapshots) {
            if (snapshot.realizedPnl().signum() == 0) {
                continue;
            }
            AssetProfile profile = profiles.get(snapshot.assetId());
            if (profile == null) {
                log.warn("Holding profile missing for assetId={}, realized PnL excluded from summary", snapshot.assetId());
                continue;
            }
            Optional<BigDecimal> realizedInBase = rates.convert(snapshot.realizedPnl(), profile.currency(), base);
            if (realizedInBase.isEmpty()) {
                unconverted.add(profile.currency().name());
                continue;
            }
            realizedPnl = realizedPnl.add(realizedInBase.get());
        }
        // 未實現損益由已折算的市值與成本相減，確保 totalMarketValue - totalCostBasis = unrealizedPnl 恆成立。
        BigDecimal unrealizedPnl = marketValue.subtract(costBasis);
        BigDecimal totalPnl = realizedPnl.add(unrealizedPnl);
        PortfolioSummaryDto dto = new PortfolioSummaryDto(
            marketValue,
            costBasis,
            realizedPnl,
            unrealizedPnl,
            totalPnl,
            roi(totalPnl, costBasis),
            positions.size(),
            base.name(),
            fxExcludedCount,
            List.copyOf(unconverted)
        );
        portfolioCache.writeSummary(userId, base, dto);
        return dto;
    }

    /**
     * 為單一持倉估值並寫入快取。
     *
     * <p>最新價與前收一律經 {@link MarketDataFacade#findDailyQuotes} 取得（Redis latest → {@code market_prices}）。
     * <strong>不得改回自己查 {@code asset_latest_prices}</strong> —— 那張表只有 V2 seed 寫過一次，
     * 沒有任何程式更新它，讀它等於讓市值永遠停在種子價；何況跨模組取資料本來就該走 Facade。
     *
     * <p>查無行情時退回平均成本估值：市值等於成本、未實現損益為零，今日損益三欄為 null。這是刻意的——
     * 寧可顯示「沒有變化」，也不要拿一個不知道多舊的價格假裝賺賠。
     *
     * <p>今日損益由 {@link DayPnlCalculator} 以 04.2 的拆股擴充點為空 list 計算；只依賴該資產今日成交與行情，
     * 所以隨持倉一起寫入快取（交易 commit 已會失效該鍵）。
     *
     * <p>估值結果存原幣（{@code baseCurrency} / {@code marketValueInBase} 為 null）。查無資產屬性
     * （理論上不會發生，FK 保證存在）時不寫快取，讓下一次請求重試，並以 null 的幣別讓呼叫端排除折算。
     *
     * @param position 持倉位置
     * @param quote    已批次取得的報價；null 表示查無行情
     * @param profile  已批次取得的資產屬性；null 表示查無
     * @param fills    該資產在其交易日起點之後的成交
     * @return 估值後的持倉 DTO
     */
    private HoldingDto calculateAndCacheHolding(
        HoldingPosition position,
        DailyQuote quote,
        AssetProfile profile,
        List<DayFill> fills
    ) {
        BigDecimal price = quote != null ? quote.latestPrice() : position.avgCost();
        OffsetDateTime priceTime = quote != null ? quote.priceTime() : position.lastUpdated();
        BigDecimal costBasis = position.totalQuantity().multiply(position.avgCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal marketValue = position.totalQuantity().multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unrealized = marketValue.subtract(costBasis);
        Optional<DayPnl> dayPnl = quote == null
            ? Optional.empty()
            : DayPnlCalculator.calculate(
                position.totalQuantity(), quote.latestPrice(), quote.previousClose(), fills, List.of());
        HoldingDto dto = new HoldingDto(
            position.assetUuid().toString(),
            position.symbol(),
            position.assetName(),
            position.totalQuantity(),
            position.avgCost(),
            costBasis,
            price,
            marketValue,
            position.realizedPnl(),
            unrealized,
            roi(unrealized, costBasis),
            priceTime,
            position.lastUpdated(),
            profile == null ? null : profile.currency().name(),
            profile == null ? null : profile.assetType().name(),
            profile == null ? null : profile.sector(),
            null,
            null,
            quote == null ? null : quote.previousClose(),
            dayPnl.map(DayPnl::pnl).orElse(null),
            dayPnl.map(DayPnl::ratio).orElse(null),
            dayPnl.map(DayPnl::basis).orElse(null)
        );
        if (profile == null) {
            log.warn("Asset profile missing for assetId={}, holding not cached", position.assetId());
            return dto;
        }
        portfolioCache.writeHolding(position.userId(), position.assetId(), dto);
        return dto;
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
