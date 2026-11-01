package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateShareAdjustmentRequest;
import dowob.xyz.stockwebv2.trading.api.ShareAdjustmentDto;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingCalculator;
import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentCalculator;
import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentEntry;
import dowob.xyz.stockwebv2.trading.domain.ShareAdjustmentType;
import dowob.xyz.stockwebv2.trading.repository.ShareAdjustmentRepository;
import dowob.xyz.stockwebv2.trading.repository.TradingRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 配股與拆股 / 合股的寫入服務（ADJ-01）。
 *
 * <p>持倉是<strong>就地改寫的物化狀態</strong>（{@code TradingService#createTrade} 不依 {@code executed_at} 重播），
 * 因此拆股不能拿目前持股乘比例：必須由帳本推出除權日起點持股 {@code Q_asOf}，把差額 ΔQ 套到目前持倉
 * （RESEARCH Q2.2、Pitfall 1）。配股的 ΔQ 就是實領股數。兩者共用「股數變、總成本不變」的
 * {@link HoldingCalculator#applyShareAdjustment}。</p>
 *
 * <p>payload 比對（409 {@code SHARE_ADJUSTMENT_IDEMPOTENCY_KEY_REUSED}）、沖銷流程與稽核由 04.2-08 補上；
 * 本版的冪等命中一律回既有那筆。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class ShareAdjustmentService {

    /**
     * 冪等鍵的長度上限，<strong>必須與 {@code share_adjustments.idempotency_key} 的 {@code VARCHAR(128)} 一致</strong>。
     */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String SYMBOL_FIELD = "symbol";
    private static final String EFFECTIVE_DATE_FIELD = "effectiveDate";
    private static final String RATIO_FIELD = "ratio";

    /**
     * 生效瞬間可超前伺服器現在的容忍量，吸收客戶端與伺服器的時鐘偏差（同 {@code EXECUTED_AT_FUTURE_TOLERANCE}）。
     */
    private static final Duration EFFECTIVE_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /**
     * 股數調整帳本的資料存取。
     */
    private final ShareAdjustmentRepository repository;

    /**
     * 持倉的資料存取：鎖定（{@code findHoldingForUpdate}）與帶 version 檢查的更新。
     */
    private final TradingRepository tradingRepository;

    /**
     * 跨模組取得標的屬性（是否可交易、市場、代號、幣別）；trading 模組不得 JOIN 資產主檔表。
     */
    private final AssetFacade assetFacade;

    /**
     * 投資組合快取；新建紀錄後讓該標的持倉鍵與 summary 失效。
     */
    private final PortfolioCache portfolioCache;

    /**
     * 時間來源；「不得超前」的判斷與持倉 {@code lastUpdated} 以它為準，測試可注入固定時鐘。
     */
    private final Clock clock;

    /**
     * 持倉計算（純函式，無狀態）。
     */
    private final HoldingCalculator calculator = new HoldingCalculator();

    /**
     * 建立股數調整寫入服務（正式環境使用系統 UTC 時鐘）。
     *
     * @param repository        股數調整帳本的資料存取
     * @param tradingRepository 持倉的資料存取
     * @param assetFacade       跨模組標的查詢
     * @param portfolioCache    投資組合快取
     */
    @Autowired
    public ShareAdjustmentService(ShareAdjustmentRepository repository, TradingRepository tradingRepository,
                                  AssetFacade assetFacade, PortfolioCache portfolioCache) {
        this(repository, tradingRepository, assetFacade, portfolioCache, Clock.systemUTC());
    }

    /**
     * 測試用建構子：可注入受控的 {@link Clock}。
     *
     * @param repository        股數調整帳本的資料存取
     * @param tradingRepository 持倉的資料存取
     * @param assetFacade       跨模組標的查詢
     * @param portfolioCache    投資組合快取
     * @param clock             時間來源，不可為 null
     */
    ShareAdjustmentService(ShareAdjustmentRepository repository, TradingRepository tradingRepository,
                           AssetFacade assetFacade, PortfolioCache portfolioCache, Clock clock) {
        this.repository = repository;
        this.tradingRepository = tradingRepository;
        this.assetFacade = assetFacade;
        this.portfolioCache = portfolioCache;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 記一筆配股或拆股 / 合股並改寫目前持倉；同一使用者的同一把冪等鍵只會寫入一列。
     *
     * <p>順序：零 I/O 的驗證先行（冪等鍵、type、依 type 的形狀）→ 解析標的 → 換算生效瞬間並擋超前 →
     * 冪等快路徑（命中不鎖、不改、不失效）→ <strong>先鎖持倉</strong>再做時序規則、{@code Q_asOf}、持倉檢查與計算
     * → insert-first 宣告這把鍵 → 更新持倉（version 檢查）→ commit 後失效快取。
     * 「寫帳本早於改持倉」與 {@code createTrade} 同理：insert 落空（重送 / 併發）時持倉連碰都不碰。</p>
     *
     * <p>每請求的 Facade 往返為 2 次（{@code findBySymbol} 解析標的與市場、{@code findProfiles} 取幣別）。</p>
     *
     * @param userId         紀錄擁有者 id，一律由 controller 自已驗證的身分取得
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，由 {@code Idempotency-Key} header 原樣傳入
     * @return 新建立的紀錄；重送時為既有紀錄
     * @throws BusinessException 欄位 / 冪等鍵非法、除權日超前或早於最晚未沖銷調整（400 {@code VALIDATION_FAILED}）、
     *                           標的不存在或不可交易（404 {@code ASSET_NOT_FOUND}）、除權日起點或目前無持倉、
     *                           調整後持股 ≤ 0（409 {@code TRADE_INSUFFICIENT_HOLDING}）、
     *                           insert 落空且以 key 重讀也落空（409 {@code DUPLICATE_RESOURCE}）、
     *                           持倉併發更新衝突（409 {@code TRADE_CONFLICT}）
     */
    @Transactional
    public ShareAdjustmentDto create(Long userId, CreateShareAdjustmentRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        validateIdempotencyKey(idempotencyKey);
        ShareAdjustmentType type = ShareAdjustmentType.fromApiValue(request.type());
        validateShape(type, request);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Facade 往返 1/2：解析標的（只有 active + tradeable 可記），同時取得換算生效瞬間所需的市場屬性。
        AssetSummary asset = resolveTradeableAsset(request.symbol());
        // 生效瞬間由後端以標的市場的交易日起點換算（與日 K / 前收 / 今日損益同一切點），
        // 不可用伺服器或客戶端的本地午夜：台北午夜會把美股除權日算到前一個紐約交易日（Pitfall 3）。
        OffsetDateTime effectiveAt = OffsetDateTime.ofInstant(
            TradingDay.of(asset.assetType(), asset.market()).startOf(request.effectiveDate()), ZoneOffset.UTC);
        if (effectiveAt.isAfter(now.plus(EFFECTIVE_AT_FUTURE_TOLERANCE))) {
            throw new FieldValidationException("Invalid effectiveDate",
                Map.of(EFFECTIVE_DATE_FIELD, "must not be in the future"));
        }

        Optional<ShareAdjustmentEntry> known = repository.findByIdempotencyKey(userId, idempotencyKey);
        if (known.isPresent()) {
            // 快路徑：資料完全沒有變動，不鎖持倉、不失效快取（payload 比對由 04.2-08 補上）。
            return toDto(known.get(), repository.isReversed(known.get().id()));
        }

        // 先鎖持倉：ΔQ 依持倉與帳本計算，鎖住後同標的的交易寫入才不會在計算與更新之間交錯（T-04.2-15）。
        Optional<Holding> current = tradingRepository.findHoldingForUpdate(userId, asset.id());
        rejectIfEarlierThanLatestActive(userId, asset.id(), effectiveAt);
        BigDecimal quantityAsOf = repository.quantityAsOf(userId, asset.id(), effectiveAt);
        if (quantityAsOf.signum() <= 0 || current.isEmpty() || current.get().totalQuantity().signum() <= 0) {
            // D-06 / D-09：除權日起點與目前都必須持有該標的；這是狀態衝突（409），不是輸入格式錯。
            throw new BusinessException(ErrorCode.TRADE_INSUFFICIENT_HOLDING,
                ErrorCode.TRADE_INSUFFICIENT_HOLDING.defaultMessage(), Map.of(SYMBOL_FIELD, "no holding"));
        }
        BigDecimal quantityDelta = switch (type) {
            case STOCK_DIVIDEND -> request.quantity();
            case SPLIT -> ShareAdjustmentCalculator.splitDelta(quantityAsOf, request.ratioNew(), request.ratioOld());
        };
        if (quantityDelta.signum() == 0) {
            // 極小零股經 scale 8 DOWN 換算後沒有任何變動：寫一筆 ΔQ = 0 的調整沒有意義，且違反 ck_share_adjustments_shape。
            throw new FieldValidationException("Split produces no quantity change",
                Map.of(RATIO_FIELD, "produces no quantity change"));
        }
        // 先算出下一個持倉（調整後持股 ≤ 0 會在這裡 409），通過後才宣告冪等鍵，被拒的請求不留帳本列。
        Holding next = calculator.applyShareAdjustment(current.get(), quantityDelta, now);

        Optional<ShareAdjustmentEntry> inserted = repository.insertIfAbsent(new ShareAdjustmentEntry(
            null,
            UUID.randomUUID(),
            userId,
            asset.id(),
            type,
            type == ShareAdjustmentType.SPLIT ? request.ratioNew() : null,
            type == ShareAdjustmentType.SPLIT ? request.ratioOld() : null,
            quantityDelta,
            request.effectiveDate(),
            effectiveAt,
            StringUtils.trimToNull(request.note()),
            null,
            idempotencyKey,
            null
        ));
        if (inserted.isEmpty()) {
            // 衝突被 DO NOTHING 吸收、不會中止本交易，因此重讀是安全的；持倉尚未改動，直接回對方那筆。
            Optional<ShareAdjustmentEntry> concurrent = repository.findByIdempotencyKey(userId, idempotencyKey);
            if (concurrent.isPresent()) {
                return toDto(concurrent.get(), repository.isReversed(concurrent.get().id()));
            }
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, ErrorCode.DUPLICATE_RESOURCE.defaultMessage());
        }
        tradingRepository.updateHolding(next);
        // 只有實際新建了列才失效：持倉股數 / 均價與 summary 都已改變。失效排在 COMMIT 之後（PortfolioCache 實作）。
        portfolioCache.invalidateAfterTrade(userId, asset.id());
        return toDto(inserted.get(), false);
    }

    /**
     * 擋下不可能是合法冪等鍵的輸入，且必須在任何 I/O 之前執行；錯誤訊息不回射鍵值本身。
     *
     * @param idempotencyKey 待驗證的冪等鍵
     * @throws FieldValidationException 鍵為空白或超過 {@value #MAX_IDEMPOTENCY_KEY_LENGTH} 字元時
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
     * 依 type 驗證必填欄位（範圍已由 bean validation 檢查）；另一種 type 的欄位不檢查，寫入時忽略。
     *
     * @param type    已解析的調整類型
     * @param request 建立請求
     * @throws FieldValidationException 除權日缺漏（{@code fields.effectiveDate}）、配股缺 quantity（{@code fields.quantity}）、
     *                                  拆股缺 ratioNew / ratioOld（{@code fields.ratioNew} / {@code fields.ratioOld}）
     *                                  或兩者相等（{@code fields.ratio}）時
     */
    private void validateShape(ShareAdjustmentType type, CreateShareAdjustmentRequest request) {
        if (request.effectiveDate() == null) {
            throw new FieldValidationException("effectiveDate is required",
                Map.of(EFFECTIVE_DATE_FIELD, "must not be null"));
        }
        switch (type) {
            case STOCK_DIVIDEND -> {
                if (request.quantity() == null || request.quantity().signum() <= 0) {
                    throw new FieldValidationException("quantity is required for STOCK_DIVIDEND",
                        Map.of("quantity", "must be greater than 0"));
                }
            }
            case SPLIT -> {
                requirePositiveRatio(request.ratioNew(), "ratioNew");
                requirePositiveRatio(request.ratioOld(), "ratioOld");
                if (request.ratioNew().equals(request.ratioOld())) {
                    throw new FieldValidationException("ratioNew must differ from ratioOld",
                        Map.of(RATIO_FIELD, "ratioNew must differ from ratioOld"));
                }
            }
        }
    }

    /**
     * 拆股比例必須存在且為正（上限由 bean validation 檢查）。
     *
     * @param ratio 比例的一側
     * @param field 錯誤時指名的欄位
     * @throws FieldValidationException 缺漏或非正時
     */
    private void requirePositiveRatio(Integer ratio, String field) {
        if (ratio == null || ratio <= 0) {
            throw new FieldValidationException(field + " is required for SPLIT", Map.of(field, "must be at least 1"));
        }
    }

    /**
     * 時序規則：新調整的生效瞬間不得早於該標的最晚一筆「非沖銷列且未被沖銷」的調整（RESEARCH Q2.4）。
     *
     * <p>較晚調整的 ΔQ 是以「不含較早事件」的 {@code Q_asOf} 算出的；插隊補記會讓兩筆都錯。
     * 要補記更早的，先沖銷較晚者。同一瞬間不視為「早於」。</p>
     *
     * @param userId      擁有者
     * @param assetId     標的主鍵
     * @param effectiveAt 本次的生效瞬間
     * @throws FieldValidationException 早於最晚有效調整時（{@code fields.effectiveDate}）
     */
    private void rejectIfEarlierThanLatestActive(Long userId, Long assetId, OffsetDateTime effectiveAt) {
        Optional<ShareAdjustmentEntry> latest = repository.findLatestActive(userId, assetId);
        if (latest.isPresent() && effectiveAt.isBefore(latest.get().effectiveAt())) {
            throw new FieldValidationException("effectiveDate is earlier than the latest share adjustment",
                Map.of(EFFECTIVE_DATE_FIELD, "must not be earlier than the latest share adjustment"));
        }
    }

    /**
     * 解析可記調整的標的：必須存在、active 且 tradeable（語意與 fields 同 {@code TradingService}）。
     *
     * @param symbol 請求中的標的代號，大小寫不敏感
     * @return 標的摘要
     * @throws BusinessException 代號空白時 {@code VALIDATION_FAILED}（fields.symbol）；
     *                           不存在、inactive 或不可交易時 {@code ASSET_NOT_FOUND}（fields.symbol）
     */
    private AssetSummary resolveTradeableAsset(String symbol) {
        if (StringUtils.isBlank(symbol)) {
            throw new FieldValidationException("symbol is required", Map.of(SYMBOL_FIELD, "must not be blank"));
        }
        AssetSummary asset = assetFacade.findBySymbol(symbol.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset not found",
                Map.of(SYMBOL_FIELD, "not found")));
        if (!asset.active() || !asset.tradeable()) {
            throw new BusinessException(ErrorCode.ASSET_NOT_FOUND, "Asset is not tradeable",
                Map.of(SYMBOL_FIELD, "not tradeable"));
        }
        return asset;
    }

    /**
     * 組出對外 DTO；代號與幣別取自<strong>該列</strong>的標的（冪等命中時不假設與本次請求同一標的）。
     *
     * @param entry    紀錄
     * @param reversed 此紀錄是否已被沖銷
     * @return 對外 DTO
     * @throws IllegalStateException 外鍵保證存在的標的卻查無 profile 時（資料不一致，訊息不含識別資料）
     */
    private ShareAdjustmentDto toDto(ShareAdjustmentEntry entry, boolean reversed) {
        // Facade 往返 2/2：幣別不存於 share_adjustments，一律取自標的的交易幣別（findProfiles 不篩 active）。
        AssetProfile profile = assetFacade.findProfiles(Set.of(entry.assetId())).get(entry.assetId());
        if (profile == null) {
            throw new IllegalStateException("Asset profile is missing for a recorded share adjustment");
        }
        String reversalOfUuid = entry.reversalOfId() == null
            ? null
            : repository.uuidOf(entry.reversalOfId()).map(UUID::toString).orElse(null);
        return new ShareAdjustmentDto(
            entry.uuid().toString(),
            profile.symbol(),
            profile.currency().name(),
            entry.type().name(),
            entry.quantityDelta(),
            entry.ratioNew(),
            entry.ratioOld(),
            entry.effectiveDate(),
            entry.createdAt(),
            entry.note(),
            reversalOfUuid,
            reversed
        );
    }
}
