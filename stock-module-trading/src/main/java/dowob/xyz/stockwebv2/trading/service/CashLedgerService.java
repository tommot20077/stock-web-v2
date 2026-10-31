package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.api.CashEntryDto;
import dowob.xyz.stockwebv2.trading.api.CreateCashEntryRequest;
import dowob.xyz.stockwebv2.trading.domain.CashEntryPayloadMatcher;
import dowob.xyz.stockwebv2.trading.domain.CashEntryType;
import dowob.xyz.stockwebv2.trading.domain.CashEntryView;
import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;
import dowob.xyz.stockwebv2.trading.repository.CashLedgerRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 現金分錄的寫入服務（CASH-01）。
 *
 * <p>現金帳<strong>只記帳</strong>：不查餘額、不擋出金（D-01 / D-08）。餘額為負只是「使用者還沒補記入金」
 * 的如實呈現，由 CASH-02 的推導負責，不在寫入路徑上設限。</p>
 *
 * <p>新建分錄（含沖銷）後讓該使用者的 summary 快取在 commit 之後失效，因為 summary 含由分錄推導的可用現金
 * （CASH-02、D-22）；冪等命中與被拒的請求沒有寫入，不失效。</p>
 *
 * @author Yuan
 * @version 2.1
 */
@Service
public class CashLedgerService {

    /**
     * 冪等鍵的長度上限，<strong>必須與 {@code cash_ledger_entries.idempotency_key} 的
     * {@code VARCHAR(128)} 一致</strong>：兩者分歧時，過長的鍵會穿過應用層驗證、在 insert 時變成
     * 與冪等命中難以區分的資料完整性例外。
     */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String REVERSAL_FIELD = "reversalOfId";

    /**
     * 發生時間可超前伺服器現在的容忍量，吸收客戶端與伺服器的時鐘偏差（同 {@code TradingService}）。
     */
    private static final Duration OCCURRED_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /**
     * 列表頁碼上限，與 {@code TradingService} 一致；超過上限的 offset 只會讓資料庫白白掃描。
     */
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final CashLedgerRepository repository;
    private final PortfolioCache portfolioCache;

    /**
     * 建立現金分錄寫入服務。
     *
     * @param repository     現金分錄帳本的資料存取
     * @param portfolioCache 投資組合快取；新建分錄後用來讓含現金項的 summary 失效
     */
    public CashLedgerService(CashLedgerRepository repository, PortfolioCache portfolioCache) {
        this.repository = repository;
        this.portfolioCache = portfolioCache;
    }

    /**
     * 記一筆入金、出金或沖銷；同一使用者的同一把冪等鍵只會寫入一列。
     *
     * <p>順序與 {@code TradingService#createTrade} 一致：零 I/O 的驗證先行，接著冪等鍵快路徑，
     * 再 insert-first。整條路徑靠 {@code ON CONFLICT DO NOTHING} 避開唯一約束例外，因此
     * <strong>不需要也不可以</strong>在本交易內 catch 例外後重讀（PostgreSQL 在例外後該交易已中止）。</p>
     *
     * <p>請求帶 {@code reversalOfId} 時為沖銷（D-25）：先以使用者範圍解析原分錄（查無即 404，別人的與不存在的
     * 無法區分），再走冪等快路徑，之後才檢查沖銷規則。<strong>冪等快路徑必須排在「已沖銷」判斷之前</strong>：
     * 一筆沖銷成功後，原分錄就「已被沖銷」；若網路逾時後以同一把 key 重送先被「已沖銷」擋下，使用者會看到
     * 假性的 409，而實際上他的沖銷早已成功。先查 key，重送才會拿回同一筆。</p>
     *
     * <p>冪等命中（快路徑或 insert 落空後重讀）時一律經 {@link CashEntryPayloadMatcher} 比對：
     * payload 相同回既有分錄，不同則 409 {@code CASH_IDEMPOTENCY_KEY_REUSED}（D-11）。</p>
     *
     * <p>insert 落空而以 key 重讀也落空，代表衝突的不是冪等鍵：沖銷時是另一把 key 的沖銷搶先
     * （{@code uk_cash_ledger_entries_reversal_of}），回 {@code CASH_ENTRY_ALREADY_REVERSED}；
     * 一般分錄則是理論殘餘競態，回 {@code DUPLICATE_RESOURCE}。</p>
     *
     * @param userId         分錄擁有者 id，一律由 controller 自已驗證的身分取得
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，由 {@code Idempotency-Key} header 原樣傳入
     * @return 新建立的分錄；重送時為既有分錄
     * @throws BusinessException 冪等鍵為空白或過長、type 或 currency 不在白名單、發生時間在未來、
     *                           reversalOfId 格式錯誤、沖銷內容與原分錄不鏡像（皆為 400 欄位錯誤）、
     *                           原分錄不存在（404）、原分錄已被沖銷或本身是沖銷（409）、
     *                           同鍵但 payload 不同，或併發下重讀落空時
     */
    @Transactional
    public CashEntryDto createEntry(Long userId, CreateCashEntryRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        validateIdempotencyKey(idempotencyKey);
        CashEntryType type = CashEntryType.fromApiValue(request.type());
        CurrencyCode currency = parseCurrency(request.currency());
        // 截到微秒：TIMESTAMPTZ 只存到微秒，不先正規化的話，帶奈秒精度的客戶端每次重送都會與
        // 讀回值差一個被四捨五入掉的尾數，於是每次合法重試都吃到假性 409（同 createTrade）。
        // requestedOccurredAt 保留「請求有沒有送」：省略時不參與冪等比對（見 CashEntryPayloadMatcher）。
        OffsetDateTime requestedOccurredAt = Optional.ofNullable(request.occurredAt())
            .map(value -> value.truncatedTo(ChronoUnit.MICROS))
            .orElse(null);
        OffsetDateTime occurredAt = Optional.ofNullable(requestedOccurredAt)
            .orElseGet(() -> OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        validateNotInFuture(requestedOccurredAt);
        UUID reversalUuid = parseReversalOfId(request.reversalOfId());
        CashLedgerEntry original = resolveOriginal(userId, reversalUuid);
        Long reversalOfId = original == null ? null : original.id();
        Optional<CashLedgerEntry> known = repository.findByIdempotencyKey(userId, idempotencyKey);
        if (known.isPresent()) {
            return resolveIdempotentHit(
                known.get(), type, currency, request.amount(), requestedOccurredAt, original);
        }
        if (original != null) {
            validateReversalRules(original, type, currency, request.amount());
        }
        Optional<CashLedgerEntry> inserted = repository.insertIfAbsent(new CashLedgerEntry(
            null,
            UUID.randomUUID(),
            userId,
            type,
            currency,
            request.amount(),
            cleanNote(request.note()),
            occurredAt,
            null,
            idempotencyKey,
            reversalOfId
        ));
        if (inserted.isEmpty()) {
            // 衝突被 DO NOTHING 吸收，不會中止本交易，因此重讀是安全的。可能的原因有二：
            // 同一把 key 的另一個請求搶先（重讀命中，走比對）；或另一把 key 的沖銷搶先沖銷了同一筆（重讀落空）。
            Optional<CashLedgerEntry> concurrent = repository.findByIdempotencyKey(userId, idempotencyKey);
            if (concurrent.isPresent()) {
                return resolveIdempotentHit(
                    concurrent.get(), type, currency, request.amount(), requestedOccurredAt, original);
            }
            ErrorCode lost = original == null ? ErrorCode.DUPLICATE_RESOURCE : ErrorCode.CASH_ENTRY_ALREADY_REVERSED;
            throw new BusinessException(lost, lost.defaultMessage());
        }
        // 只有實際新建了列才失效：此時 summary 的現金項已變。冪等命中（上面兩處）資料未變，不失效。
        // 失效排在本交易 COMMIT 之後（見 PortfolioCache#invalidateSummaryAfterCommit），讀者不會在 commit 前
        // 重算出記帳前的現金再寫回快取。
        portfolioCache.invalidateSummaryAfterCommit(userId);
        return toDto(viewOf(inserted.get(), original));
    }

    /**
     * 解析請求的 {@code reversalOfId}；未帶（null）代表一般入出金。
     *
     * <p>錯誤訊息為靜態字串，不回射輸入。非 null 但為空白或不是 UUID 一律視為格式錯誤，
     * 而不是當作「沒帶」—— 否則一個本意是沖銷、卻帶了空字串的請求，會默默變成一筆真的出金。</p>
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
     * 以使用者範圍解析沖銷的原分錄。
     *
     * @param userId       目前登入者
     * @param reversalUuid 請求指定的原分錄 UUID；null 代表不是沖銷
     * @return 原分錄；不是沖銷時為 null
     * @throws BusinessException 查無（含屬於他人）時 {@code CASH_ENTRY_NOT_FOUND}，訊息不含 UUID
     */
    private CashLedgerEntry resolveOriginal(Long userId, UUID reversalUuid) {
        if (reversalUuid == null) {
            return null;
        }
        return repository.findByUuid(userId, reversalUuid)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.CASH_ENTRY_NOT_FOUND, ErrorCode.CASH_ENTRY_NOT_FOUND.defaultMessage()));
    }

    /**
     * 沖銷規則的應用層檢查；與 V15 的 {@code validate_cash_ledger_reversal} trigger 一致，
     * 差別是這裡回的是前端可分派的錯誤碼，trigger 只是繞過應用層時的最後防線。
     *
     * @param original 已解析的原分錄
     * @param type     本次請求的方向
     * @param currency 本次請求的幣別
     * @param amount   本次請求的金額
     * @throws BusinessException        原分錄本身是沖銷 {@code CASH_ENTRY_NOT_REVERSIBLE}，
     *                                  或已被沖銷 {@code CASH_ENTRY_ALREADY_REVERSED}
     * @throws FieldValidationException 方向未相反、幣別或金額不同時，fields 指名 {@code reversalOfId}
     */
    private void validateReversalRules(
        CashLedgerEntry original, CashEntryType type, CurrencyCode currency, BigDecimal amount) {
        if (original.reversalOfId() != null) {
            throw new BusinessException(
                ErrorCode.CASH_ENTRY_NOT_REVERSIBLE, ErrorCode.CASH_ENTRY_NOT_REVERSIBLE.defaultMessage());
        }
        if (repository.existsReversalOf(original.id())) {
            throw new BusinessException(
                ErrorCode.CASH_ENTRY_ALREADY_REVERSED, ErrorCode.CASH_ENTRY_ALREADY_REVERSED.defaultMessage());
        }
        if (type != original.type().opposite()
            || currency != original.currency()
            || amount.compareTo(original.amount()) != 0) {
            throw new FieldValidationException("Reversal must mirror the original entry",
                Map.of(REVERSAL_FIELD, "must mirror the original entry"));
        }
    }

    /**
     * 冪等命中時的 payload 比對：相同回既有分錄，不同則拒絕且不寫入。
     *
     * <p>錯誤訊息為靜態字串，不回射冪等鍵、金額或備註（T-04.1-13）。沖銷對象也是比對維度：
     * 同一把 key 換了沖銷對象，是不同的意圖。</p>
     *
     * @param existing            以同一把鍵查出的既有分錄
     * @param type                本次請求的方向
     * @param currency            本次請求的幣別
     * @param amount              本次請求的金額
     * @param requestedOccurredAt 本次請求明確送出的發生時間（已截到微秒）；未送為 null
     * @param original            本次請求解析出的原分錄；一般分錄為 null
     * @return 既有分錄的 DTO
     * @throws BusinessException payload 與既有分錄不同時，{@code CASH_IDEMPOTENCY_KEY_REUSED}
     */
    private CashEntryDto resolveIdempotentHit(
        CashLedgerEntry existing,
        CashEntryType type,
        CurrencyCode currency,
        BigDecimal amount,
        OffsetDateTime requestedOccurredAt,
        CashLedgerEntry original
    ) {
        Long requestedReversalOfId = original == null ? null : original.id();
        if (!CashEntryPayloadMatcher.matches(
            existing, type, currency, amount, requestedOccurredAt, requestedReversalOfId)) {
            throw new BusinessException(
                ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED, ErrorCode.CASH_IDEMPOTENCY_KEY_REUSED.defaultMessage());
        }
        return toDto(viewOf(existing, original));
    }

    /**
     * 組出寫入路徑回傳用的視圖：沖銷分錄帶原分錄的 UUID 與發生時間；剛寫入的分錄不可能已被沖銷。
     */
    private CashEntryView viewOf(CashLedgerEntry entry, CashLedgerEntry original) {
        return new CashEntryView(
            entry,
            original == null ? null : original.uuid(),
            original == null ? null : original.occurredAt(),
            false
        );
    }

    /**
     * 取回登入者自己的一頁現金分錄，發生時間新到舊、同時間以 id 新到舊（D-24）。
     *
     * <p>標記為<strong>可重複讀</strong>的唯讀交易：repository 內 count 與 list 是兩道獨立述句，
     * PostgreSQL 預設的 READ COMMITTED 下兩者各取自己的快照，併發寫入落在兩者之間時
     * {@code totalElements} 會與 {@code items} 漂移；{@code readOnly = true} 本身不改變隔離層級，
     * 所以必須明確指定 {@code REPEATABLE_READ}（PR #50 Codex review）。</p>
     *
     * @param userId 分錄擁有者 id，一律由 controller 自已驗證的身分取得；其他使用者的分錄永遠不會出現
     * @param page   頁碼，超出範圍會被夾限至 0..10000
     * @param size   每頁筆數，超出範圍會被夾限至 1..100
     * @return 該頁分錄 DTO（含 {@code reversed} 與指回原分錄的資訊）與該使用者的分錄總數
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<CashEntryDto> listEntries(Long userId, int page, int size) {
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        PageResponse<CashEntryView> views = repository.listEntries(userId, safePage, safeSize);
        return PageResponse.of(
            views.items().stream().map(this::toDto).toList(), views.page(), views.size(), views.totalElements());
    }

    /**
     * 擋下不可能是合法冪等鍵的輸入，且必須在任何 I/O 之前執行。
     *
     * <p>錯誤訊息只說明期望、<strong>不回射鍵值本身</strong>：鍵是完全使用者可控的字串（T-04-03）。</p>
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
     * 擋下超前現在過多的發生時間：入出金是「已發生」的紀錄，不是預約。
     *
     * <p>只檢查請求明確送出的值；省略時伺服器自行取「現在」，不可能在未來。訊息為靜態字串。</p>
     *
     * @param requestedOccurredAt 請求明確送出的發生時間；未送為 null
     * @throws FieldValidationException 超前現在超過 5 分鐘時，
     *                                  fields 指名 {@code occurredAt}
     */
    private void validateNotInFuture(OffsetDateTime requestedOccurredAt) {
        if (requestedOccurredAt != null
            && requestedOccurredAt.isAfter(OffsetDateTime.now().plus(OCCURRED_AT_FUTURE_TOLERANCE))) {
            throw new FieldValidationException("Invalid occurredAt",
                Map.of("occurredAt", "must not be in the future"));
        }
    }

    /**
     * 解析幣別白名單，大小寫不敏感；訊息為靜態字串、不回射輸入。
     *
     * @param value 使用者傳入的原始字串
     * @return 對應的幣別
     * @throws FieldValidationException 值為空白或不在白名單內時，fields 指名 {@code currency}
     */
    private CurrencyCode parseCurrency(String value) {
        if (StringUtils.isNotBlank(value)) {
            for (CurrencyCode candidate : CurrencyCode.values()) {
                if (candidate.name().equalsIgnoreCase(value.trim())) {
                    return candidate;
                }
            }
        }
        throw new FieldValidationException("Unsupported currency", Map.of("currency", "unsupported currency"));
    }

    /**
     * 備註去除前後空白；全空白視為未填。
     */
    private String cleanNote(String note) {
        return StringUtils.trimToNull(note);
    }

    private CashEntryDto toDto(CashEntryView view) {
        CashLedgerEntry entry = view.entry();
        return new CashEntryDto(
            entry.uuid().toString(),
            entry.type().name(),
            entry.currency().name(),
            entry.amount(),
            entry.note(),
            entry.occurredAt(),
            entry.createdAt(),
            Optional.ofNullable(view.reversalOfUuid()).map(UUID::toString).orElse(null),
            view.reversalOfOccurredAt(),
            view.reversed()
        );
    }
}
