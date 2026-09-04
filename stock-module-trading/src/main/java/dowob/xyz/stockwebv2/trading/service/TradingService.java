package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser.RangeBound;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.marketdata.LatestMarketPrice;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateTradeRequest;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;
import dowob.xyz.stockwebv2.trading.api.TradeDto;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingCalculator;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.domain.TradePayloadMatcher;
import dowob.xyz.stockwebv2.trading.domain.TradeSortKey;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;
import dowob.xyz.stockwebv2.trading.domain.TradeType;
import dowob.xyz.stockwebv2.trading.repository.TradeQuery;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradingService {
    private static final int MAX_PAGE = 10_000;
    private static final int MONEY_SCALE = 8;

    /**
     * {@code executedAt} 容許超前伺服器時鐘的幅度。
     *
     * <p>成交時間由提交者填寫，補登舊交易是明確支援的情境，故<strong>不設下界</strong>；
     * 但未來時間沒有合理用途，且會讓該筆交易落在任何「至今為止」的區間查詢之外。
     * 保留數分鐘容忍度是為了吸收客戶端與伺服器之間正常的時鐘偏移，避免誤殺合法請求。</p>
     */
    private static final Duration EXECUTED_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /**
     * 冪等鍵的長度上限，<strong>必須與 {@code transactions.idempotency_key} 的
     * {@code VARCHAR(128)} 一致</strong>：兩者一旦分歧，過長的鍵就會穿過應用層驗證、
     * 在 insert 時變成資料完整性例外，而那個例外與冪等命中難以區分。
     */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TradingRepository repository;
    private final AssetFacade assetFacade;
    private final MarketDataFacade marketDataFacade;
    private final PortfolioCache portfolioCache;
    private final TradingMapper mapper;
    private final HoldingCalculator calculator;

    public TradingService(
        TradingRepository repository,
        AssetFacade assetFacade,
        MarketDataFacade marketDataFacade,
        PortfolioCache portfolioCache,
        TradingMapper mapper
    ) {
        this.repository = repository;
        this.assetFacade = assetFacade;
        this.marketDataFacade = marketDataFacade;
        this.portfolioCache = portfolioCache;
        this.mapper = mapper;
        this.calculator = new HoldingCalculator();
    }

    /**
     * 建立一筆交易並更新持倉；同一使用者的同一把冪等鍵只會建立一筆交易。
     *
     * <p><strong>交易寫入必須早於持倉變更。</strong>持倉是就地改寫的狀態，交易則是 append-only 帳本：
     * 若先改持倉再靠 insert 的唯一約束發現重送，重複的那次改動已經發生，而帳本上改不回來。
     * 因此本方法先以 {@link TradingRepository#insertTransactionIfAbsent} 宣告「這把鍵歸我」，
     * 拿到列之後才動持倉；重送的請求連持倉分支都不會進入。</p>
     *
     * <p><strong>資產解析必須早於冪等鍵快路徑。</strong>命中既有交易時要以
     * {@link TradePayloadMatcher} 比對 payload，而比對維度是 {@code assetId} 而非標的代號
     * （代號是否唯一未經查證，比 id 就不依賴那個假設）。代價是重送也要付一次資產解析，
     * 但 D-07 的正確性優先於省一次查詢。</p>
     *
     * <p>整條路徑靠 {@code ON CONFLICT DO NOTHING} 避開唯一約束例外，因此<strong>不需要也不可以</strong>
     * 在本交易內 catch 例外後重讀：PostgreSQL 在唯一約束例外後該交易已中止，重讀必定失敗。</p>
     *
     * @param userId         交易擁有者 id，一律由 controller 自已驗證的身分取得
     * @param request        交易建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，由 {@code Idempotency-Key} header 原樣傳入
     * @return 新建立的交易；重送時為既有交易
     * @throws BusinessException 冪等鍵為空白或超過長度上限時丟出 VALIDATION_FAILED；
     *                           同一把鍵搭配不同 payload 時丟出 TRADE_IDEMPOTENCY_KEY_REUSED；
     *                           以及既有的標的不存在、交易類型不支援、持倉不足、成交時間為未來時間等情境
     */
    @Transactional
    public TradeDto createTrade(Long userId, CreateTradeRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        validateIdempotencyKey(idempotencyKey);
        OffsetDateTime now = OffsetDateTime.now();
        // 驗證順序與 listTrades 一致：零 I/O 的白名單比對與時間檢查先行，需查資料庫的 symbol
        // 解析最後。必定會被拒絕的請求因此不必先付一次資產查詢，也不會在交易內做無謂的工作。
        TradeType type = TradeType.fromApiValue(request.type());
        BigDecimal fee = Objects.requireNonNullElse(request.fee(), BigDecimal.ZERO);
        // 截到微秒：TIMESTAMPTZ 只存到微秒，不先正規化的話，帶奈秒精度的客戶端每次重送都會與
        // 讀回值差一個被四捨五入掉的尾數，於是每次合法重試都吃到假性 409（DP-7）。
        OffsetDateTime executedAt = resolveExecutedAt(request.executedAt(), now).truncatedTo(ChronoUnit.MICROS);
        AssetSummary asset = resolveTradeableAsset(request.symbol());
        Optional<TradeTransaction> known = repository.findByIdempotencyKey(userId, idempotencyKey);
        if (known.isPresent()) {
            // 快路徑：資料完全沒有變動，因此也不必讓快取失效（DP-8）。
            return resolveIdempotentHit(known.get(), asset.id(), type, request, fee, executedAt);
        }
        Optional<TradeTransaction> inserted = repository.insertTransactionIfAbsent(new TradeTransaction(
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
            null,
            idempotencyKey
        ));
        if (inserted.isEmpty()) {
            // 併發下被同一把鍵的另一個請求搶先。DO NOTHING 不會中止本交易，因此重讀是安全的；
            // 重讀仍落空是理論上的殘餘競態，一律回 409 而非讓它變成 500。
            TradeTransaction concurrent = repository.findByIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CONFLICT, ErrorCode.TRADE_CONFLICT.defaultMessage()));
            return resolveIdempotentHit(concurrent, asset.id(), type, request, fee, executedAt);
        }
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
        portfolioCache.invalidateAfterTrade(userId, asset.id());
        return mapper.toTradeDto(inserted.get());
    }

    /**
     * 擋下不可能是合法冪等鍵的輸入，且必須在任何 I/O 之前執行。
     *
     * <p>空白鍵會通過 header 的 {@code required = true}（{@code Idempotency-Key:} 是合法的空值），
     * 若放行則第一個空字串會落進部分唯一索引，把所有後續請求都變成衝突。過長的鍵若只靠
     * 欄位上限攔截，會在 insert 時拋出與「冪等命中」難以區分的資料完整性例外（T-04-04）。</p>
     *
     * <p>錯誤訊息刻意只說明期望、<strong>不回射鍵值本身</strong>：鍵是完全使用者可控的字串，
     * 而錯誤訊息是使用者可見的輸出面（T-04-03）。</p>
     *
     * @param idempotencyKey 待驗證的冪等鍵
     * @throws FieldValidationException 鍵為空白或超過 {@value #MAX_IDEMPOTENCY_KEY_LENGTH} 字元時丟出
     *                                  VALIDATION_FAILED，fields 以 header 名指出問題所在（D-16）
     */
    private void validateIdempotencyKey(String idempotencyKey) {
        if (StringUtils.isBlank(idempotencyKey)) {
            throw new FieldValidationException("Idempotency-Key header is required",
                Map.of(IDEMPOTENCY_KEY_HEADER, "must not be blank"));
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new FieldValidationException("Idempotency-Key header exceeds the maximum length",
                Map.of(IDEMPOTENCY_KEY_HEADER, "must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters"));
        }
    }

    /**
     * 處理冪等鍵命中既有交易的情形：相同意圖回傳既有交易，不同意圖回報衝突（D-07）。
     *
     * <p>兩條路徑（快路徑與併發重讀）共用這個方法，避免兩處各寫一次比對而逐漸分歧。</p>
     *
     * @param existing   既有交易
     * @param assetId    本次請求解析出的標的 id
     * @param type       本次請求的交易類型
     * @param request    本次請求
     * @param fee        本次請求已套用預設值的手續費
     * @param executedAt 本次請求已正規化的成交時間
     * @return 既有交易的 DTO
     * @throws BusinessException payload 與既有交易不一致時丟出 TRADE_IDEMPOTENCY_KEY_REUSED
     */
    private TradeDto resolveIdempotentHit(
        TradeTransaction existing,
        Long assetId,
        TradeType type,
        CreateTradeRequest request,
        BigDecimal fee,
        OffsetDateTime executedAt
    ) {
        if (!TradePayloadMatcher.matches(existing, assetId, type, request.quantity(), request.price(), fee, executedAt)) {
            throw new BusinessException(
                ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED,
                ErrorCode.TRADE_IDEMPOTENCY_KEY_REUSED.defaultMessage()
            );
        }
        return mapper.toTradeDto(existing);
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
        OffsetDateTime from = ApiTimeParser.parseRangeBound(dateFrom, "dateFrom", RangeBound.LOWER);
        OffsetDateTime to = ApiTimeParser.parseRangeBound(dateTo, "dateTo", RangeBound.UPPER);
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

    /**
     * 為單一持倉估值並寫入快取。
     *
     * <p>最新價一律經 {@link MarketDataFacade} 取得（Redis latest → {@code market_prices}）。
     * <strong>不得改回自己查 {@code asset_latest_prices}</strong> —— 那張表只有 V2 seed 寫過一次，
     * 沒有任何程式更新它，讀它等於讓市值永遠停在種子價；何況跨模組取資料本來就該走 Facade。
     *
     * <p>查無行情時退回平均成本估值：市值等於成本、未實現損益為零。這是刻意的——寧可顯示「沒有變化」，
     * 也不要拿一個不知道多舊的價格假裝賺賠。
     *
     * @param position 持倉位置
     * @return 估值後的持倉 DTO
     */
    private HoldingDto calculateAndCacheHolding(HoldingPosition position) {
        LatestMarketPrice latest = marketDataFacade.findLatestPrice(position.assetId())
            .orElseGet(() -> new LatestMarketPrice(position.avgCost(), position.lastUpdated()));
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
