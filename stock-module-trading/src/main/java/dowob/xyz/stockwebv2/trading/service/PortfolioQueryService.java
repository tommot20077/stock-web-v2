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
import dowob.xyz.stockwebv2.trading.api.CashBalanceDto;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.domain.AllocationCalculator;
import dowob.xyz.stockwebv2.trading.domain.CashBalanceCalculator;
import dowob.xyz.stockwebv2.trading.domain.CashFlow;
import dowob.xyz.stockwebv2.trading.domain.DayFill;
import dowob.xyz.stockwebv2.trading.domain.DayPnl;
import dowob.xyz.stockwebv2.trading.domain.DayPnlCalculator;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.HoldingSnapshot;
import dowob.xyz.stockwebv2.trading.repository.CashLedgerRepository;
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
 * <p>CASH-02 起 summary 帶可用現金（D-06 ~ D-10、D-22）：入出金淨額（{@link CashLedgerRepository#netByCurrency}）
 * 加上交易現金流（{@link TradingRepository#tradeCashFlowByAsset}，幣別取自標的、D-07），由
 * {@link CashBalanceCalculator} 依幣別加總，不存任何餘額欄位。<strong>現金項放在 summary 快取之內</strong>，
 * 所以寫入端必須使快取失效：交易走 {@link PortfolioCache#invalidateAfterTrade}、入出金（含沖銷）走
 * {@link PortfolioCache#invalidateSummaryAfterCommit}，兩者皆排在 commit 之後。本類別不把現金另外算在快取之外——
 * 那會讓命中路徑多兩道 SQL，抵銷快取的意義；失效時機由 {@code PortfolioCashAllocationIT} 的
 * {@code depositRaisesCashImmediatelyAfterCommit} 證明。</p>
 *
 * <p>PORT-12 起 summary 帶資產配置與產業分組（D-18 ~ D-21）：以上面已折算成基準幣的持倉市值與未實現損益、
 * 以及基準幣現金，交給純函式 {@link AllocationCalculator} 分組，匯率只在後端折算一次。配置計算不呼叫任何 Facade，
 * 快取未命中時每個 Facade 至多一次批次呼叫（共 4 次，D-23）的預算不變，見 {@link #summary} 內的呼叫點註解；
 * 現金的兩道聚合 SQL 是本模組自己的表，不算 Facade。</p>
 *
 * @author Yuan
 * @version 5.0
 */
@Service
public class PortfolioQueryService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioQueryService.class);
    private static final int MONEY_SCALE = 8;

    /** 今日損益平倉候選的回溯天數；涵蓋週末與連假（A8）。 */
    private static final int CLOSED_CANDIDATE_DAYS = 7;

    private final TradingRepository repository;
    private final CashLedgerRepository cashLedgerRepository;
    private final MarketDataFacade marketDataFacade;
    private final PortfolioCache portfolioCache;
    private final UserPreferenceFacade userPreferenceFacade;
    private final AssetFacade assetFacade;
    private final FxRateFacade fxRateFacade;

    /**
     * 建立讀時計算服務。
     *
     * @param repository       交易與持倉資料存取
     * @param cashLedgerRepository 現金分錄帳本的聚合查詢（可用現金推導）
     * @param marketDataFacade 跨模組最新價查詢
     * @param portfolioCache   持倉與摘要的 Redis 快取
     * @param userPreferenceFacade 跨模組取得使用者基準幣
     * @param assetFacade      跨模組批次取得資產屬性（幣別等）
     * @param fxRateFacade     跨模組取得目前匯率
     */
    public PortfolioQueryService(
        TradingRepository repository,
        CashLedgerRepository cashLedgerRepository,
        MarketDataFacade marketDataFacade,
        PortfolioCache portfolioCache,
        UserPreferenceFacade userPreferenceFacade,
        AssetFacade assetFacade,
        FxRateFacade fxRateFacade
    ) {
        this.repository = repository;
        this.cashLedgerRepository = cashLedgerRepository;
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
        List<HoldingDto> original = valuePositions(userId, positions, null, Set.of()).holdings();
        FxRates rates = fxRateFacade.currentRates();
        return original.stream().map(holding -> withBaseValuation(holding, base, rates)).toList();
    }

    /**
     * 為一組持倉位置估值（原幣）：批次讀持倉快取，對未命中者一次批次取資產屬性、報價與今日成交並寫回快取。
     *
     * @param userId        使用者 id
     * @param positions     持倉位置
     * @param knownProfiles 呼叫端已批次取得的資產屬性；null 表示尚未取得，未命中時才由本方法一次批次取得
     * @param closedIds     今日損益的平倉候選資產 id（summary 專用，持倉列表傳空 set）；
     *                      其報價與持倉未命中者<strong>合併成同一次</strong> {@code findDailyQuotes}，需有 {@code knownProfiles}
     * @return 與 {@code positions} 同順序的原幣估值 DTO（{@code baseCurrency} / {@code marketValueInBase} 為 null），
     *         以及這次取回的報價與今日成交
     */
    private Valuation valuePositions(
        Long userId,
        List<HoldingPosition> positions,
        Map<Long, AssetProfile> knownProfiles,
        Set<Long> closedIds
    ) {
        if (positions.isEmpty() && closedIds.isEmpty()) {
            return new Valuation(List.of(), DayContext.EMPTY);
        }
        List<Long> assetIds = positions.stream().map(HoldingPosition::assetId).toList();
        Map<Long, HoldingDto> cached = positions.isEmpty() ? Map.of() : portfolioCache.readHoldings(userId, assetIds);
        List<Long> misses = assetIds.stream().filter(id -> !cached.containsKey(id)).toList();
        Set<Long> quoteIds = new LinkedHashSet<>(misses);
        quoteIds.addAll(closedIds);
        Map<Long, AssetProfile> profiles = Map.of();
        DayContext day = DayContext.EMPTY;
        if (!quoteIds.isEmpty()) {
            profiles = knownProfiles != null ? knownProfiles : assetFacade.findProfiles(misses);
            day = loadDayContext(userId, quoteIds, closedIds, profiles);
        }
        Map<Long, AssetProfile> missProfiles = profiles;
        DayContext missDay = day;
        List<HoldingDto> holdings = positions.stream()
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
        return new Valuation(holdings, day);
    }

    /**
     * 持倉估值與同一次請求取回的報價、今日成交。
     *
     * @param holdings 與持倉位置同順序的原幣估值
     * @param day      報價與今日成交
     */
    private record Valuation(List<HoldingDto> holdings, DayContext day) {
    }

    /**
     * 批次取得一組資產的報價與今日成交：一次 {@code findDailyQuotes}、至多一次 {@code listFillsSince}。
     *
     * <p>交易日規則由資產屬性建立（{@code TradingDay.of(assetType, market)}）；資產屬性缺失時退回 UTC，
     * 讓行情仍可取得。各資產的交易日起點以<strong>與 market-data 相同的公式</strong>
     * {@code tradingDay.dayStart(quote.priceTime().toInstant())} 計算，兩邊才會逐位元一致。
     * 平倉候選若沒有行情，交易日起點退而取 {@code tradingDay.dayStart(Instant.now())}，
     * 才能判斷它今天有沒有成交。今日成交以所有交易日起點的最小值一次查詢，再逐資產以各自的起點過濾。
     *
     * @param userId    使用者 id
     * @param assetIds  要取價的資產 id
     * @param closedIds 其中屬於平倉候選者
     * @param profiles  已取得的資產屬性
     * @return 報價與各資產今日成交；沒有行情的資產不在 {@code quotes} 內
     */
    private DayContext loadDayContext(
        Long userId,
        Collection<Long> assetIds,
        Set<Long> closedIds,
        Map<Long, AssetProfile> profiles
    ) {
        Map<Long, TradingDay> tradingDays = new LinkedHashMap<>();
        for (Long assetId : assetIds) {
            AssetProfile profile = profiles.get(assetId);
            tradingDays.put(assetId, profile == null ? TradingDay.UTC : TradingDay.of(profile.assetType(), profile.market()));
        }
        Map<Long, DailyQuote> quotes = marketDataFacade.findDailyQuotes(tradingDays);
        Map<Long, Instant> dayStarts = new HashMap<>();
        quotes.forEach((assetId, quote) -> dayStarts.put(
            assetId, tradingDays.getOrDefault(assetId, TradingDay.UTC).dayStart(quote.priceTime().toInstant())));
        Instant now = Instant.now();
        for (Long closedId : closedIds) {
            dayStarts.computeIfAbsent(closedId, id -> tradingDays.getOrDefault(id, TradingDay.UTC).dayStart(now));
        }
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
     * <p>今日價格損益（D-12 ~ D-17、D-26）：候選 = 持有數量大於 0 的持倉，加上 {@code last_updated} 在近 7 天內的
     * 平倉列（賣出會以現在更新 {@code last_updated}；7 天涵蓋週末與連假，某市場超過 7 天沒有任何 tick
     * 的極端情形為已知限制）。平倉列只有今天有成交才算候選——今天賣光的部位不在持倉列表，但其
     * {@code S×(ps−C)} 必須計入，否則使用者今天停損的損失在總額裡會憑空消失。
     * 每個候選的損益與分母依各自幣別以<strong>目前匯率</strong>折算後加總（只含價格損益，不含匯差）。
     * <strong>null 與 0 的差異</strong>：沒有任何候選 → {@code 0}（真實的零）；有候選但全部不可得 → {@code null}
     * （缺資料，不塞 0）；否則為已計入者的總和，不可得的檔數回報於 {@code dayPnlExcludedCount}。
     *
     * <p>可用現金（D-06 ~ D-10、D-22）：入出金淨額與各標的交易現金流（以標的幣別計）依幣別加總為
     * {@code cashBalances}（原幣、可為負），逐幣別折成基準幣後加總為 {@code cash}，
     * {@code totalEquity = totalMarketValue + cash}；{@code roi} 不含現金。換不到匯率的現金幣別不計入 {@code cash}，
     * 列入 {@code unconvertedCurrencies}（D-04）。交易資產的幣別與持倉共用同一次 {@code findProfiles}。
     * 此方法不驗證餘額、不擋任何操作（D-01）。快取失效由寫入端負責，見類別說明。
     *
     * <p>資產配置（D-18 ~ D-21）：「可折算且持有數量 &gt; 0」的持倉（換不到匯率或缺資產屬性者不進配置，與總額一致）
     * 以基準幣市值依 {@code assetType} 分類並加上正現金成為 {@code allocation}；依 {@code sector} 原值分組成
     * {@code sectorAllocation}（不含現金，null 產業排最後）。兩個分母的差異與排序規則見
     * {@link AllocationCalculator}。
     *
     * @param userId 使用者 id
     * @return 投資組合摘要 DTO
     */
    @Transactional(readOnly = true)
    public PortfolioSummaryDto summary(Long userId) {
        // Facade 呼叫點（D-23：快取未命中時 4 次批次呼叫，皆為批次、結果有快取、命中路徑只有第 1 次）：
        // 1. userPreferenceFacade.findBaseCurrency — 取基準幣，同時是 summary 快取鍵的一部分，必須先於快取讀取。
        // 2. assetFacade.findProfiles — 一次取全部持倉（含已平倉）的幣別，供市值與已實現損益折算。
        // 3. marketDataFacade.findDailyQuotes — 持倉快取未命中者 ∪ 近 7 天平倉候選，合併成同一次批次取最新價與前收
        //    （見 valuePositions）。今日成交 repository.listFillsSince 是本模組自己的表，至多一次，不算 Facade。
        // 4. fxRateFacade.currentRates — 一次取回完整匯率表，之後逐筆折算在記憶體完成。
        // 四者分屬 user / asset / market-data / asset(fx) 不同資料來源，無法合併成同一次往返。
        CurrencyCode base = userPreferenceFacade.findBaseCurrency(userId);
        Optional<PortfolioSummaryDto> cached = portfolioCache.readSummary(userId, base);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<HoldingSnapshot> snapshots = repository.listHoldingSnapshots(userId);
        List<HoldingPosition> positions = repository.listHoldings(userId);
        // 現金的兩道聚合 SQL 是本模組自己的表（不是 Facade）。交易資產的幣別併入下面同一次 findProfiles。
        Map<Long, BigDecimal> tradeCashFlows = repository.tradeCashFlowByAsset(userId);
        Map<CurrencyCode, BigDecimal> ledgerNet = cashLedgerRepository.netByCurrency(userId);
        Set<Long> assetIds = new LinkedHashSet<>();
        snapshots.forEach(snapshot -> assetIds.add(snapshot.assetId()));
        positions.forEach(position -> assetIds.add(position.assetId()));
        assetIds.addAll(tradeCashFlows.keySet());
        Map<Long, AssetProfile> profiles = assetIds.isEmpty() ? Map.of() : assetFacade.findProfiles(assetIds);
        Set<Long> closedIds = closedCandidateIds(snapshots);
        Valuation valuation = valuePositions(userId, positions, profiles, closedIds);
        List<HoldingDto> holdings = valuation.holdings();
        FxRates rates = fxRateFacade.currentRates();

        Set<String> unconverted = new TreeSet<>();
        int fxExcludedCount = 0;
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        List<AllocationCalculator.Input> allocationInputs = new ArrayList<>();
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
            // 配置輸入沿用上面已折算的值（D-21）：未實現損益 = 折算後市值 − 折算後成本，與 summary 總額同一算法。
            allocationInputs.add(new AllocationCalculator.Input(
                holding.assetType(),
                holding.sector(),
                marketValueInBase.get(),
                marketValueInBase.get().subtract(costBasisInBase.get())));
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
        DayTotals dayTotals = dayTotals(base, rates, positions, holdings, closedIds, valuation.day(), profiles, unconverted);
        CashSummary cashSummary = cashSummary(base, rates, ledgerNet, tradeCashFlows, profiles, unconverted);
        // 純函式：只吃已折算的持倉值與基準幣現金，不另呼叫任何 Facade（D-23 的呼叫預算不變）。
        AllocationCalculator.Result allocation = AllocationCalculator.calculate(allocationInputs, cashSummary.cash());
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
            List.copyOf(unconverted),
            dayTotals.pnl(),
            dayTotals.ratio(),
            dayTotals.excludedCount(),
            cashSummary.cash(),
            marketValue.add(cashSummary.cash()),
            cashSummary.balances(),
            allocation.allocation(),
            allocation.sectors()
        );
        portfolioCache.writeSummary(userId, base, dto);
        return dto;
    }

    /**
     * 推導各幣別現金餘額，並折成基準幣合計。
     *
     * <p>現金流 = 入出金淨額（以帳本幣別）＋ 各標的交易現金流（以標的幣別，D-07），交給
     * {@link CashBalanceCalculator#balances} 依幣別加總；加總後逐幣別以目前匯率折成基準幣。
     * 換不到匯率的幣別不計入合計，改記入 {@code unconverted}（D-04），其原幣餘額仍列在 {@code balances}。
     * 取不到資產屬性的交易標的無從得知幣別，略過並記 WARN（理論上不會發生，FK 保證存在），
     * 與持倉缺屬性時的處理一致。
     *
     * @param base         使用者基準幣
     * @param rates        目前匯率
     * @param ledgerNet    入出金淨額（幣別 → 金額）
     * @param tradeFlows   交易現金流（標的 id → 金額，標的幣別）
     * @param profiles     資產屬性
     * @param unconverted  換不到匯率的幣別（會被加入）
     * @return 基準幣合計與各幣別原幣餘額
     */
    private CashSummary cashSummary(
        CurrencyCode base,
        FxRates rates,
        Map<CurrencyCode, BigDecimal> ledgerNet,
        Map<Long, BigDecimal> tradeFlows,
        Map<Long, AssetProfile> profiles,
        Set<String> unconverted
    ) {
        List<CashFlow> flows = new ArrayList<>();
        ledgerNet.forEach((currency, amount) -> flows.add(new CashFlow(currency, amount)));
        tradeFlows.forEach((assetId, amount) -> {
            AssetProfile profile = profiles.get(assetId);
            if (profile == null) {
                log.warn("Trade asset profile missing for assetId={}, trade cash flow excluded from cash", assetId);
                return;
            }
            flows.add(new CashFlow(profile.currency(), amount));
        });
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(flows);
        BigDecimal cash = BigDecimal.ZERO.setScale(MONEY_SCALE);
        List<CashBalanceDto> balanceDtos = new ArrayList<>();
        for (Map.Entry<CurrencyCode, BigDecimal> balance : balances.entrySet()) {
            balanceDtos.add(new CashBalanceDto(balance.getKey().name(), balance.getValue()));
            Optional<BigDecimal> inBase = rates.convert(balance.getValue(), balance.getKey(), base);
            if (inBase.isEmpty()) {
                unconverted.add(balance.getKey().name());
                continue;
            }
            cash = cash.add(inBase.get());
        }
        return new CashSummary(cash, List.copyOf(balanceDtos));
    }

    /**
     * 可用現金的基準幣合計與各幣別原幣餘額。
     *
     * @param cash     基準幣合計（不含換不到匯率的幣別）
     * @param balances 各幣別原幣餘額，依 {@link CurrencyCode} 宣告順序
     */
    private record CashSummary(BigDecimal cash, List<CashBalanceDto> balances) {
    }

    /**
     * 今日損益的平倉候選：持有數量為 0 且 {@code last_updated} 在近 7 天內的持倉列（A8）。
     *
     * @param snapshots 該使用者所有持倉快照
     * @return 平倉候選的資產 id
     */
    private Set<Long> closedCandidateIds(List<HoldingSnapshot> snapshots) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(CLOSED_CANDIDATE_DAYS);
        Set<Long> closedIds = new LinkedHashSet<>();
        for (HoldingSnapshot snapshot : snapshots) {
            if (snapshot.totalQuantity().signum() == 0 && !snapshot.lastUpdated().isBefore(since)) {
                closedIds.add(snapshot.assetId());
            }
        }
        return closedIds;
    }

    /**
     * 彙總今日價格損益總額（基準幣）。
     *
     * <p>持倉列的單檔損益與分母直接取自（可能來自快取的）{@link HoldingDto}；平倉候選則以本次請求取回的報價與
     * 今日成交計算。不可得者計入排除數；換不到匯率者改記入 {@code unconverted}（D-04），不計入排除數。
     *
     * @param base        使用者基準幣
     * @param rates       目前匯率
     * @param positions   持有數量大於 0 的持倉位置
     * @param holdings    與 {@code positions} 同順序的估值
     * @param closedIds   平倉候選資產 id
     * @param day         本次取回的報價與今日成交
     * @param profiles    資產屬性
     * @param unconverted 換不到匯率的幣別（會被加入）
     * @return 總額、比率與排除數
     */
    private DayTotals dayTotals(
        CurrencyCode base,
        FxRates rates,
        List<HoldingPosition> positions,
        List<HoldingDto> holdings,
        Set<Long> closedIds,
        DayContext day,
        Map<Long, AssetProfile> profiles,
        Set<String> unconverted
    ) {
        DayAccumulator total = new DayAccumulator(base, rates, unconverted);
        for (int i = 0; i < positions.size(); i++) {
            HoldingDto holding = holdings.get(i);
            Optional<DayPnl> dayPnl = holding.dayPricePnl() == null || holding.dayPnlBasis() == null
                ? Optional.empty()
                : Optional.of(new DayPnl(holding.dayPricePnl(), holding.dayPnlBasis()));
            total.add(profiles.get(positions.get(i).assetId()), dayPnl);
        }
        for (Long closedId : closedIds) {
            List<DayFill> fills = day.fillsOf(closedId);
            if (fills.isEmpty()) {
                // 平倉後今天沒有成交：不是候選，也不算排除。
                continue;
            }
            DailyQuote quote = day.quotes().get(closedId);
            Optional<DayPnl> dayPnl = quote == null
                ? Optional.empty()
                : DayPnlCalculator.calculate(
                    BigDecimal.ZERO, quote.latestPrice(), quote.previousClose(), fills, List.of());
            total.add(profiles.get(closedId), dayPnl);
        }
        return total.result();
    }

    /**
     * 今日損益總額、比率與排除數。
     *
     * @param pnl           總額（基準幣）；null = 缺資料，0 = 沒有任何候選
     * @param ratio         比率；總額為 null 或分母為零時為 null
     * @param excludedCount 因價格缺失而未計入的檔數
     */
    private record DayTotals(BigDecimal pnl, BigDecimal ratio, int excludedCount) {
    }

    /**
     * 逐候選折算並累加今日損益的可變累加器，僅限單次 {@link #summary} 內使用。
     */
    private static final class DayAccumulator {
        private final CurrencyCode base;
        private final FxRates rates;
        private final Set<String> unconverted;
        private BigDecimal pnl = BigDecimal.ZERO;
        private BigDecimal basis = BigDecimal.ZERO;
        private int candidates;
        private int included;
        private int excluded;

        DayAccumulator(CurrencyCode base, FxRates rates, Set<String> unconverted) {
            this.base = base;
            this.rates = rates;
            this.unconverted = unconverted;
        }

        /**
         * 加入一個候選。
         *
         * @param profile 資產屬性；null 時無從折算，視同換不到匯率（該持倉已被 fxExcludedCount 計入）
         * @param dayPnl  該候選的原幣今日損益；empty 表示價格缺失
         */
        void add(AssetProfile profile, Optional<DayPnl> dayPnl) {
            candidates++;
            if (dayPnl.isEmpty()) {
                excluded++;
                return;
            }
            if (profile == null) {
                return;
            }
            Optional<BigDecimal> pnlInBase = rates.convert(dayPnl.get().pnl(), profile.currency(), base);
            Optional<BigDecimal> basisInBase = rates.convert(dayPnl.get().basis(), profile.currency(), base);
            if (pnlInBase.isEmpty() || basisInBase.isEmpty()) {
                unconverted.add(profile.currency().name());
                return;
            }
            pnl = pnl.add(pnlInBase.get());
            basis = basis.add(basisInBase.get());
            included++;
        }

        DayTotals result() {
            if (candidates == 0) {
                return new DayTotals(BigDecimal.ZERO, null, 0);
            }
            if (included == 0) {
                return new DayTotals(null, null, excluded);
            }
            BigDecimal ratio = basis.signum() == 0 ? null : pnl.divide(basis, MONEY_SCALE, RoundingMode.HALF_UP);
            return new DayTotals(pnl, ratio, excluded);
        }
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
