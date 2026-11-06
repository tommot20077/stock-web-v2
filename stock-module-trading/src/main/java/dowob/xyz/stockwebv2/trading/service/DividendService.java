package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.CreateDividendRequest;
import dowob.xyz.stockwebv2.trading.api.DividendDto;
import dowob.xyz.stockwebv2.trading.domain.CashDividend;
import dowob.xyz.stockwebv2.trading.domain.DividendPayloadMatcher;
import dowob.xyz.stockwebv2.trading.repository.DividendRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 現金股利的寫入服務（DIV-01）。
 *
 * <p>股利<strong>不動股數與平均成本</strong>（D-01），因此寫入路徑完全不讀也不改持倉：
 * 從未持有或已賣光的標的同樣可記（D-03，股利常在賣出後才入帳）。</p>
 *
 * <p>新建紀錄後讓該使用者的 summary 快取在 commit 之後失效（summary 含可用現金與股利收入）；持倉快取鍵不動，
 * 因為持倉估值與股利無關。冪等命中與被拒的請求沒有寫入，不失效。</p>
 *
 * <p>更正的唯一方式是<strong>沖銷</strong>（請求帶 {@code reversalOfId}，04.2-04）：沖銷列存與原列相同的正值、
 * 指回原列，聚合時以 {@code reversal_of_id IS NOT NULL} 反號。規則與 {@code CashLedgerService} 的沖銷一致，
 * 錯誤碼換成股利專屬的 {@code DIVIDEND_*}；V16 的 {@code validate_cash_dividend_reversal} trigger 與
 * {@code uk_cash_dividends_reversal_of} 是繞過應用層時的最後防線。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@Service
public class DividendService {

    /**
     * 冪等鍵的長度上限，<strong>必須與 {@code cash_dividends.idempotency_key} 的 {@code VARCHAR(128)} 一致</strong>：
     * 兩者分歧時，過長的鍵會穿過應用層驗證、在 insert 時變成與冪等命中難以區分的資料完整性例外。
     */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String SYMBOL_FIELD = "symbol";
    private static final String REVERSAL_FIELD = "reversalOfId";
    private static final String MUST_MATCH_ORIGINAL = "must match the original dividend";

    /**
     * 入帳時間可超前伺服器現在的容忍量，吸收客戶端與伺服器的時鐘偏差（同 {@code CashLedgerService}）。
     */
    private static final Duration PAID_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /**
     * 入帳時間的下限（含）。Java / Jackson 接受帶號擴充年份（例如 {@code -10000-01-01T00:00:00Z}），
     * PostgreSQL {@code timestamptz} 卻只到西元前 4713 年；沒有下限時極端值會在 insert 才炸成 500
     * （04.2 REVIEW WR-04），合理範圍內但荒謬的年份（如 0001）也會被靜默收下。
     */
    static final OffsetDateTime MIN_PAID_AT = OffsetDateTime.parse("1900-01-01T00:00:00Z");

    /**
     * 紀錄的資料存取。
     */
    private final DividendRepository repository;

    /**
     * 跨模組取得標的屬性（是否可交易、代號、幣別）；trading 模組不得 JOIN 資產主檔表。
     */
    private final AssetFacade assetFacade;

    /**
     * 投資組合快取；新建紀錄後用來讓含現金與股利收入的 summary 失效。
     */
    private final PortfolioCache portfolioCache;

    /**
     * 時間來源；入帳時間的預設值與「不得在未來」的判斷都以它為準，測試可注入固定時鐘。
     */
    private final Clock clock;

    /**
     * 建立現金股利寫入服務（正式環境使用系統 UTC 時鐘）。
     *
     * @param repository     現金股利帳本的資料存取
     * @param assetFacade    跨模組標的查詢
     * @param portfolioCache 投資組合快取
     */
    @Autowired
    public DividendService(DividendRepository repository, AssetFacade assetFacade, PortfolioCache portfolioCache) {
        this(repository, assetFacade, portfolioCache, Clock.systemUTC());
    }

    /**
     * 測試用建構子：可注入受控的 {@link Clock}。
     *
     * @param repository     現金股利帳本的資料存取
     * @param assetFacade    跨模組標的查詢
     * @param portfolioCache 投資組合快取
     * @param clock          時間來源，不可為 null
     */
    DividendService(DividendRepository repository, AssetFacade assetFacade, PortfolioCache portfolioCache,
                    Clock clock) {
        this.repository = repository;
        this.assetFacade = assetFacade;
        this.portfolioCache = portfolioCache;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 記一筆現金股利；同一使用者的同一把冪等鍵只會寫入一列。
     *
     * <p>順序與 {@code CashLedgerService#createEntry} 一致：零 I/O 的驗證先行（冪等鍵、入帳時間），
     * 接著解析標的，再走冪等鍵快路徑，最後 insert-first。整條路徑靠 {@code ON CONFLICT DO NOTHING}
     * 避開唯一約束例外，因此<strong>不需要也不可以</strong>在本交易內 catch 例外後重讀
     * （PostgreSQL 在例外後該交易已中止）。</p>
     *
     * <p>每請求的 Facade 往返為 2 次（{@code findBySymbol} 解析標的、{@code findProfiles} 取幣別），
     * 在 ≤ 3 的預算內；持倉相關的資料存取一次都沒有（D-01 / D-03）。</p>
     *
     * <p>冪等命中（快路徑或 insert 落空後重讀）一律經 {@link DividendPayloadMatcher} 比對：payload 相同回既有列
     * 且不失效快取，不同則 409 {@code DIVIDEND_IDEMPOTENCY_KEY_REUSED}。</p>
     *
     * <p>沖銷（{@code reversalOfId} 非 null）：原列以使用者範圍解析，且<strong>排在冪等快路徑之前</strong>
     * （快路徑的比對需要原列的內部 id）；沖銷規則則<strong>排在快路徑之後</strong>——沖銷成功後以同一把 key 重送時
     * 原列已是「已沖銷」，若先檢查規則，使用者會看到假性 409，而他的沖銷其實早已成功。</p>
     *
     * @param userId         紀錄擁有者 id，一律由 controller 自已驗證的身分取得
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，由 {@code Idempotency-Key} header 原樣傳入
     * @return 新建立的紀錄；重送時為既有紀錄
     * @throws BusinessException 請求為 null、冪等鍵空白或過長、入帳時間在未來、reversalOfId 格式錯、
     *                           沖銷值與原列不符（400 {@code VALIDATION_FAILED}，fields 指名）、
     *                           標的不存在 / inactive / 不可交易（404 {@code ASSET_NOT_FOUND}）、
     *                           沖銷對象不存在或屬於他人（404 {@code DIVIDEND_NOT_FOUND}）、
     *                           沖銷對象已被沖銷，含併發沖銷落敗（409 {@code DIVIDEND_ALREADY_REVERSED}）、
     *                           沖銷對象本身是沖銷（409 {@code DIVIDEND_NOT_REVERSIBLE}）、
     *                           同一把鍵搭配不同 payload（409 {@code DIVIDEND_IDEMPOTENCY_KEY_REUSED}），
     *                           或一般紀錄 insert 落空而以 key 重讀也落空（409 {@code DUPLICATE_RESOURCE}）
     */
    @Transactional
    public DividendDto createDividend(Long userId, CreateDividendRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        validateIdempotencyKey(idempotencyKey);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 截到微秒：TIMESTAMPTZ 只存到微秒，不先正規化的話，帶奈秒精度的客戶端每次重送都會與讀回值差一個
        // 被捨入掉的尾數，之後的 payload 比對（04.2-04）就會吃到假性 409（同 CashLedgerService）。
        OffsetDateTime requestedPaidAt = Optional.ofNullable(request.paidAt())
            .map(value -> value.truncatedTo(ChronoUnit.MICROS))
            .orElse(null);
        validateNotInFuture(requestedPaidAt, now);
        validateNotBeforeFloor(requestedPaidAt);
        OffsetDateTime paidAt = Optional.ofNullable(requestedPaidAt)
            .orElseGet(() -> now.truncatedTo(ChronoUnit.MICROS));
        BigDecimal withholdingTax = Optional.ofNullable(request.withholdingTax()).orElse(BigDecimal.ZERO);
        UUID reversalUuid = parseReversalOfId(request.reversalOfId());
        // Facade 往返 1/2：解析標的（只有 active + tradeable 可記）。
        AssetSummary asset = resolveTradeableAsset(request.symbol());
        // 沖銷對象以使用者範圍解析（查無即 404，別人的與不存在的無法區分）。
        CashDividend original = resolveOriginal(userId, reversalUuid);

        Optional<CashDividend> known = repository.findByIdempotencyKey(userId, idempotencyKey);
        if (known.isPresent()) {
            // 快路徑：資料完全沒有變動，不失效快取；payload 不同則 409。
            return resolveIdempotentHit(known.get(), asset.id(), request.amount(), withholdingTax, requestedPaidAt,
                original);
        }
        if (original != null) {
            validateReversalRules(original, asset.id(), request.amount(), withholdingTax);
        }
        Optional<CashDividend> inserted = repository.insertIfAbsent(new CashDividend(
            null,
            UUID.randomUUID(),
            userId,
            asset.id(),
            request.amount(),
            withholdingTax,
            StringUtils.trimToNull(request.note()),
            paidAt,
            null,
            idempotencyKey,
            original == null ? null : original.id()
        ));
        if (inserted.isEmpty()) {
            // 衝突被 DO NOTHING 吸收、不會中止本交易，因此重讀是安全的。可能的原因有二：同一把 key 的另一個請求搶先
            // （重讀命中）；或另一把 key 的沖銷搶先沖了同一筆（uk_cash_dividends_reversal_of，重讀落空）。
            Optional<CashDividend> concurrent = repository.findByIdempotencyKey(userId, idempotencyKey);
            if (concurrent.isPresent()) {
                return resolveIdempotentHit(concurrent.get(), asset.id(), request.amount(), withholdingTax,
                    requestedPaidAt, original);
            }
            ErrorCode lost = original == null ? ErrorCode.DUPLICATE_RESOURCE : ErrorCode.DIVIDEND_ALREADY_REVERSED;
            throw new BusinessException(lost, lost.defaultMessage());
        }
        // 只有實際新建了列才失效：此時 summary 的現金與股利收入已變；冪等命中（上面兩處）資料未變，不失效。
        // 失效排在本交易 COMMIT 之後，讀者不會在 commit 前重算出記帳前的值再寫回快取。持倉快取鍵不動。
        portfolioCache.invalidateSummaryAfterCommit(userId);
        // 剛寫入的紀錄不可能已被沖銷，不必再查一次。
        return toDto(inserted.get(), false);
    }

    /**
     * 冪等命中時的 payload 比對（快路徑與 insert 落空重讀共用，避免兩處各寫一次而逐漸分歧）：
     * 相同回既有列，不同則拒絕且不寫入。錯誤訊息為靜態字串，不回射冪等鍵、金額或備註。
     *
     * @param existing        以同一把鍵查出的既有列
     * @param assetId         本次請求解析出的標的主鍵
     * @param amount          本次請求的實領金額
     * @param withholdingTax  本次請求的預扣稅（省略已正規化為 0）
     * @param requestedPaidAt 本次請求明確送出的入帳時間（已截到微秒）；未送為 null
     * @param original        本次請求解析出的沖銷原列；一般紀錄為 null
     * @return 既有列的 DTO（{@code reversed} 讀自帳本）
     * @throws BusinessException payload 不同時 {@code DIVIDEND_IDEMPOTENCY_KEY_REUSED}
     */
    private DividendDto resolveIdempotentHit(CashDividend existing, Long assetId, BigDecimal amount,
                                             BigDecimal withholdingTax, OffsetDateTime requestedPaidAt,
                                             CashDividend original) {
        Long requestedReversalOfId = original == null ? null : original.id();
        if (!DividendPayloadMatcher.matches(existing, assetId, amount, withholdingTax, requestedPaidAt,
            requestedReversalOfId)) {
            throw new BusinessException(ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED,
                ErrorCode.DIVIDEND_IDEMPOTENCY_KEY_REUSED.defaultMessage());
        }
        return toDto(existing, repository.isReversed(existing.id()));
    }

    /**
     * 解析請求的 {@code reversalOfId}；未帶（null）代表一般紀錄。
     *
     * <p>非 null 但為空白或不是 UUID 一律視為格式錯誤，而不是當作「沒帶」——否則本意是沖銷、卻帶了空字串的請求
     * 會默默變成一筆新的股利收入。錯誤訊息為靜態字串，不回射輸入。</p>
     *
     * @param value 請求中的原始字串
     * @return 解析出的 UUID；未帶時為 null
     * @throws FieldValidationException 格式錯誤時，fields 指名 {@code reversalOfId}
     */
    private UUID parseReversalOfId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new FieldValidationException("Invalid reversalOfId", Map.of(REVERSAL_FIELD, "invalid format"));
        }
    }

    /**
     * 以使用者範圍解析沖銷的原列。
     *
     * @param userId       目前登入者
     * @param reversalUuid 請求指定的原列 UUID；null 代表不是沖銷
     * @return 原列；不是沖銷時為 null
     * @throws BusinessException 查無（含屬於他人）時 {@code DIVIDEND_NOT_FOUND}，訊息不含 UUID
     */
    private CashDividend resolveOriginal(Long userId, UUID reversalUuid) {
        if (reversalUuid == null) {
            return null;
        }
        return repository.findByUuid(userId, reversalUuid)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.DIVIDEND_NOT_FOUND, ErrorCode.DIVIDEND_NOT_FOUND.defaultMessage()));
    }

    /**
     * 沖銷規則的應用層檢查；與 V16 的 {@code validate_cash_dividend_reversal} trigger 一致，差別是這裡回的是
     * 前端可分派的錯誤碼，trigger 只是繞過應用層時的最後防線。
     *
     * <p>先檢查零 I/O 的「原列是沖銷」，再查「已被沖銷」，最後逐欄比對值（數值以 compareTo，scale 不同視為相同）；
     * 不符的欄位全部列在 fields 中，訊息為靜態字串、不回射輸入。</p>
     *
     * @param original       已解析的原列
     * @param assetId        本次請求解析出的標的主鍵
     * @param amount         本次請求的實領金額
     * @param withholdingTax 本次請求的預扣稅（省略已正規化為 0）
     * @throws BusinessException        原列本身是沖銷 {@code DIVIDEND_NOT_REVERSIBLE}，
     *                                  或已被沖銷 {@code DIVIDEND_ALREADY_REVERSED}
     * @throws FieldValidationException symbol / amount / withholdingTax 與原列不同時，fields 指名不符欄位
     */
    private void validateReversalRules(CashDividend original, Long assetId, BigDecimal amount,
                                       BigDecimal withholdingTax) {
        if (original.reversalOfId() != null) {
            throw new BusinessException(
                ErrorCode.DIVIDEND_NOT_REVERSIBLE, ErrorCode.DIVIDEND_NOT_REVERSIBLE.defaultMessage());
        }
        if (repository.isReversed(original.id())) {
            throw new BusinessException(
                ErrorCode.DIVIDEND_ALREADY_REVERSED, ErrorCode.DIVIDEND_ALREADY_REVERSED.defaultMessage());
        }
        Map<String, String> mismatches = new LinkedHashMap<>();
        if (!original.assetId().equals(assetId)) {
            mismatches.put(SYMBOL_FIELD, MUST_MATCH_ORIGINAL);
        }
        if (amount.compareTo(original.amount()) != 0) {
            mismatches.put("amount", MUST_MATCH_ORIGINAL);
        }
        if (withholdingTax.compareTo(original.withholdingTax()) != 0) {
            mismatches.put("withholdingTax", MUST_MATCH_ORIGINAL);
        }
        if (!mismatches.isEmpty()) {
            throw new FieldValidationException("Reversal must match the original dividend", mismatches);
        }
    }

    /**
     * 擋下不可能是合法冪等鍵的輸入，且必須在任何 I/O 之前執行；錯誤訊息不回射鍵值本身。
     *
     * @param idempotencyKey 待驗證的冪等鍵
     * @throws FieldValidationException 鍵為空白或超過 {@value #MAX_IDEMPOTENCY_KEY_LENGTH} 字元時，
     *                                  fields 以 header 名指出問題所在
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
     * 擋下超前現在過多的入帳時間：股利是「已入帳」的紀錄，不是預約。只檢查請求明確送出的值。
     *
     * @param requestedPaidAt 請求明確送出的入帳時間（已截到微秒）；未送為 null
     * @param now             本次請求的伺服器現在
     * @throws FieldValidationException 超前現在超過 5 分鐘時，fields 指名 {@code paidAt}
     */
    private void validateNotInFuture(OffsetDateTime requestedPaidAt, OffsetDateTime now) {
        if (requestedPaidAt != null && requestedPaidAt.isAfter(now.plus(PAID_AT_FUTURE_TOLERANCE))) {
            throw new FieldValidationException("Invalid paidAt", Map.of("paidAt", "must not be in the future"));
        }
    }

    /**
     * 擋下早於 {@link #MIN_PAID_AT}（1900-01-01T00:00:00Z，含邊界）的入帳時間，在解析標的之前回 400，
     * 不讓 DB 存不下的值落到 insert 變成 500。訊息為靜態英文，不回射輸入。只檢查請求明確送出的值。
     *
     * @param requestedPaidAt 請求明確送出的入帳時間（已截到微秒）；未送為 null
     * @throws FieldValidationException 早於下限時，fields 指名 {@code paidAt}
     */
    private void validateNotBeforeFloor(OffsetDateTime requestedPaidAt) {
        if (requestedPaidAt != null && requestedPaidAt.isBefore(MIN_PAID_AT)) {
            throw new FieldValidationException("Invalid paidAt", Map.of("paidAt", "must not be before 1900-01-01"));
        }
    }

    /**
     * 解析可記股利的標的：必須存在、active 且 tradeable（語意與 fields 同 {@code TradingService}）。
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
     * @param dividend 紀錄
     * @param reversed 此紀錄是否已被沖銷
     * @return 對外 DTO
     * @throws IllegalStateException 外鍵保證存在的標的卻查無 profile 時（資料不一致，訊息不含識別資料）
     */
    private DividendDto toDto(CashDividend dividend, boolean reversed) {
        // Facade 往返 2/2：幣別不存於 cash_dividends，一律取自標的的交易幣別（findProfiles 不篩 active）。
        AssetProfile profile = assetFacade.findProfiles(Set.of(dividend.assetId())).get(dividend.assetId());
        if (profile == null) {
            throw new IllegalStateException("Asset profile is missing for a recorded dividend");
        }
        String reversalOfUuid = dividend.reversalOfId() == null
            ? null
            : repository.uuidOf(dividend.reversalOfId()).map(UUID::toString).orElse(null);
        return new DividendDto(
            dividend.uuid().toString(),
            profile.symbol(),
            profile.currency().name(),
            dividend.amount(),
            dividend.withholdingTax(),
            dividend.paidAt(),
            dividend.createdAt(),
            dividend.note(),
            reversalOfUuid,
            reversed
        );
    }
}
