package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.api.CashEntryDto;
import dowob.xyz.stockwebv2.trading.api.CreateCashEntryRequest;
import dowob.xyz.stockwebv2.trading.domain.CashEntryType;
import dowob.xyz.stockwebv2.trading.domain.CashLedgerEntry;
import dowob.xyz.stockwebv2.trading.repository.CashLedgerRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * @author Yuan
 * @version 1.0
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

    private final CashLedgerRepository repository;

    public CashLedgerService(CashLedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * 記一筆入金或出金；同一使用者的同一把冪等鍵只會寫入一列。
     *
     * <p>順序與 {@code TradingService#createTrade} 一致：零 I/O 的驗證先行，接著冪等鍵快路徑，
     * 再 insert-first。整條路徑靠 {@code ON CONFLICT DO NOTHING} 避開唯一約束例外，因此
     * <strong>不需要也不可以</strong>在本交易內 catch 例外後重讀（PostgreSQL 在例外後該交易已中止）。</p>
     *
     * <p>本方法目前只有 insert-first 骨架：冪等命中時直接回既有分錄，payload 比對與
     * {@code CASH_IDEMPOTENCY_KEY_REUSED} 由 04.1-22 補上。</p>
     *
     * @param userId         分錄擁有者 id，一律由 controller 自已驗證的身分取得
     * @param request        建立請求，已通過 bean validation
     * @param idempotencyKey 冪等鍵，由 {@code Idempotency-Key} header 原樣傳入
     * @return 新建立的分錄；重送時為既有分錄
     * @throws BusinessException 冪等鍵為空白或過長、type 或 currency 不在白名單，或併發下重讀落空時
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
        OffsetDateTime occurredAt = Optional.ofNullable(request.occurredAt())
            .orElseGet(OffsetDateTime::now)
            .truncatedTo(ChronoUnit.MICROS);
        Optional<CashLedgerEntry> known = repository.findByIdempotencyKey(userId, idempotencyKey);
        if (known.isPresent()) {
            return toDto(known.get());
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
            null
        ));
        if (inserted.isEmpty()) {
            // 併發下被同一把鍵的另一個請求搶先。DO NOTHING 不會中止本交易，因此重讀是安全的；
            // 重讀仍落空是理論上的殘餘競態，一律回 409 而非讓它變成 500。
            CashLedgerEntry concurrent = repository.findByIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CONFLICT, ErrorCode.TRADE_CONFLICT.defaultMessage()));
            return toDto(concurrent);
        }
        return toDto(inserted.get());
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

    private CashEntryDto toDto(CashLedgerEntry entry) {
        return new CashEntryDto(
            entry.uuid().toString(),
            entry.type().name(),
            entry.currency().name(),
            entry.amount(),
            entry.note(),
            entry.occurredAt(),
            entry.createdAt(),
            null,
            null,
            false
        );
    }
}
