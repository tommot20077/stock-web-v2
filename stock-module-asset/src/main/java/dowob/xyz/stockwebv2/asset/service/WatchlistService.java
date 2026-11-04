package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.WatchlistDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.domain.Watchlist;
import dowob.xyz.stockwebv2.asset.repository.WatchlistRepository;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.TradingDay;
import dowob.xyz.stockwebv2.infrastructure.marketdata.DailyQuote;
import dowob.xyz.stockwebv2.infrastructure.marketdata.MarketDataFacade;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 觀察清單（WATCH-01 / WATCH-02；D-14 ~ D-19）。契約見 {@code ai-docs/watchlist-contract.md}。
 *
 * <p>回傳的每檔項目都經 {@link AssetQuoteMapper}——與 {@code GET /api/v1/assets} 同一段程式計算漲跌與量縮寫；
 * 每次回應只呼叫一次 {@link MarketDataFacade#findDailyQuotes}（以 distinct 資產批次）。</p>
 *
 * <p>擁有權：所有以 UUID 定位的操作都經 repository 綁 {@code user_id} 查詢，不存在與屬於他人一律
 * {@code WATCHLIST_NOT_FOUND}（不洩漏存在性）。錯誤訊息與 {@code fields} 原因皆為靜態字串，不回射清單名稱。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class WatchlistService {

    /** 預設清單名稱（D-17）。 */
    public static final String DEFAULT_NAME = Watchlist.DEFAULT_NAME;

    /** 每位使用者的清單上限（D-17）。 */
    public static final int MAX_WATCHLISTS = 20;

    /** 每份清單的項目上限（D-17）。 */
    public static final int MAX_ITEMS = 100;

    /** 清單名稱 trim 後的長度上限（以 code point 計，與 PostgreSQL VARCHAR(30) 的字元計數一致）。 */
    static final int MAX_NAME_LENGTH = 30;

    private final WatchlistRepository watchlistRepository;
    private final MarketDataFacade marketDataFacade;

    /**
     * @param watchlistRepository 觀察清單儲存庫
     * @param marketDataFacade    行情 facade，提供各資產市場交易日的報價統計
     */
    public WatchlistService(WatchlistRepository watchlistRepository, MarketDataFacade marketDataFacade) {
        this.watchlistRepository = watchlistRepository;
        this.marketDataFacade = marketDataFacade;
    }

    /**
     * 列出使用者全部清單與每檔報價；使用者 0 個清單時先建立「我的自選」（刪光後下一次呼叫會重建）。
     *
     * @param userId 擁有者，一律由 controller 自已驗證的身分取得
     * @return 依 created_at, id 排序的清單；項目依 position 排序
     */
    @Transactional
    public List<WatchlistDto> list(Long userId) {
        watchlistRepository.ensureDefault(userId);
        return toDtos(watchlistRepository.listByUser(userId));
    }

    /**
     * 建立具名清單。先確保預設清單存在（保證至少有一列可鎖），再鎖住該使用者全部清單列、鎖後重新計數：
     * 並發建立因此序列化，20 個上限是硬的。
     *
     * @param userId 擁有者
     * @param name   清單名稱，trim 後 1–30 字
     * @return 新建的清單（項目為空）
     * @throws BusinessException 名稱非法（400 {@code fields.name}）、已達 20 個（400 {@code fields.watchlists}）、
     *                           同使用者重名（409 {@code DUPLICATE_RESOURCE}，{@code fields.name}）
     */
    @Transactional
    public WatchlistDto create(Long userId, String name) {
        String normalized = normalizeName(name);
        watchlistRepository.ensureDefault(userId);
        watchlistRepository.lockAllForUser(userId);
        // 必須是鎖之後的另一個查詢：READ COMMITTED 下 lockAllForUser 的快照在等鎖前取得，看不到剛 commit 的新列。
        if (watchlistRepository.countByUser(userId) >= MAX_WATCHLISTS) {
            throw new FieldValidationException("Watchlist limit reached", Map.of("watchlists", "at most 20 watchlists"));
        }
        Watchlist created = watchlistRepository.insertIfNameFree(userId, normalized)
            .orElseThrow(WatchlistService::duplicateName);
        return toDtos(List.of(created)).get(0);
    }

    /**
     * 改名。
     *
     * @param userId 擁有者
     * @param uuid   清單 UUID
     * @param name   新名稱，trim 後 1–30 字
     * @return 改名後的清單（含項目與報價）
     * @throws BusinessException 名稱非法（400 {@code fields.name}）、清單不存在或屬於他人（404
     *                           {@code WATCHLIST_NOT_FOUND}）、與另一份清單重名（409 {@code DUPLICATE_RESOURCE}）
     */
    @Transactional
    public WatchlistDto rename(Long userId, UUID uuid, String name) {
        String normalized = normalizeName(name);
        Watchlist existing = watchlistRepository.findByUuidForUpdate(userId, uuid)
            .orElseThrow(WatchlistService::notFound);
        if (!watchlistRepository.rename(existing.id(), userId, normalized)) {
            throw duplicateName();
        }
        Watchlist renamed = new Watchlist(existing.id(), existing.uuid(), existing.userId(), normalized, existing.createdAt());
        return toDtos(List.of(renamed)).get(0);
    }

    /**
     * 刪除清單；項目由 {@code ON DELETE CASCADE} 一併刪除。刪光後下一次 {@link #list} 會重建「我的自選」。
     *
     * @param userId 擁有者
     * @param uuid   清單 UUID
     * @throws BusinessException 清單不存在或屬於他人（404 {@code WATCHLIST_NOT_FOUND}）
     */
    @Transactional
    public void delete(Long userId, UUID uuid) {
        Watchlist existing = watchlistRepository.findByUuidForUpdate(userId, uuid)
            .orElseThrow(WatchlistService::notFound);
        watchlistRepository.delete(existing.id(), userId);
    }

    /**
     * 把資產加到清單尾端（D-15、D-17、D-18）。
     *
     * <p>順序：{@code findByUuidForUpdate} 鎖清單列 → 解析 symbol（只要求 active，不要求 tradeable）→
     * 已在清單中則原樣回傳 → 以<strong>鎖後另起</strong>的 {@code countItems} 檢查 100 檔上限 → 插入尾端。
     * 計數不能沿用鎖查詢本身的結果：READ COMMITTED 下鎖查詢的快照在等鎖前取得，看不到前一個持鎖交易剛
     * commit 的項目，會讓並發加入超過上限。</p>
     *
     * @param userId        擁有者
     * @param watchlistUuid 清單 UUID
     * @param symbol        資產代號（前後空白會被去除）
     * @return 加入後的清單（含項目與報價）；已在清單中時為原樣
     * @throws BusinessException 清單不存在或屬於他人（404 {@code WATCHLIST_NOT_FOUND}）、資產不存在或 inactive
     *                           （404 {@code ASSET_NOT_FOUND}，{@code fields.symbol}）、清單已有 100 檔
     *                           （400 {@code VALIDATION_FAILED}，{@code fields.items}）
     */
    @Transactional
    public WatchlistDto addItem(Long userId, UUID watchlistUuid, String symbol) {
        Watchlist watchlist = watchlistRepository.findByUuidForUpdate(userId, watchlistUuid)
            .orElseThrow(WatchlistService::notFound);
        Long assetId = Optional.ofNullable(symbol)
            .map(String::strip)
            .filter(normalized -> !normalized.isEmpty())
            .flatMap(watchlistRepository::findActiveAssetIdBySymbol)
            .orElseThrow(WatchlistService::assetNotFound);
        if (watchlistRepository.containsItem(watchlist.id(), assetId)) {
            return toDto(watchlist);
        }
        // 必須是鎖之後的另一個查詢：READ COMMITTED 下鎖查詢的快照在等鎖前取得，看不到剛 commit 的項目。
        if (watchlistRepository.countItems(watchlist.id()) >= MAX_ITEMS) {
            throw new FieldValidationException("Watchlist item limit reached", Map.of("items", "at most 100 items"));
        }
        watchlistRepository.insertItemAtEnd(watchlist.id(), assetId);
        return toDto(watchlist);
    }

    /**
     * RED skeleton（04.2-12）。
     *
     * @param userId        擁有者
     * @param watchlistUuid 清單 UUID
     * @param assetUuid     資產 UUID
     * @return 移除後的清單
     */
    @Transactional
    public WatchlistDto removeItem(Long userId, UUID watchlistUuid, UUID assetUuid) {
        return null;
    }

    /**
     * RED skeleton（04.2-12）。
     *
     * @param userId        擁有者
     * @param watchlistUuid 清單 UUID
     * @param assetUuids    新順序
     * @return 重排後的清單
     */
    @Transactional
    public WatchlistDto reorder(Long userId, UUID watchlistUuid, List<UUID> assetUuids) {
        return null;
    }

    /**
     * 名稱正規化：去除前後空白（{@link String#strip()}，含全形空白），trim 後須為 1–30 個 code point。
     *
     * @param name 使用者輸入
     * @return 正規化後的名稱
     * @throws FieldValidationException 名稱為空或超長時（{@code fields.name}）
     */
    static String normalizeName(String name) {
        String stripped = name == null ? "" : name.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length == 0 || length > MAX_NAME_LENGTH) {
            throw new FieldValidationException("Watchlist name is invalid", Map.of("name", "must be 1 to 30 characters"));
        }
        return stripped;
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, ErrorCode.DUPLICATE_RESOURCE.defaultMessage(),
            Map.of("name", "already exists"));
    }

    private static BusinessException assetNotFound() {
        return new BusinessException(ErrorCode.ASSET_NOT_FOUND, ErrorCode.ASSET_NOT_FOUND.defaultMessage(),
            Map.of("symbol", "not found"));
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND, ErrorCode.WATCHLIST_NOT_FOUND.defaultMessage());
    }

    private WatchlistDto toDto(Watchlist watchlist) {
        return toDtos(List.of(watchlist)).get(0);
    }

    private List<WatchlistDto> toDtos(List<Watchlist> watchlists) {
        if (watchlists.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Asset>> items = watchlistRepository.itemsByWatchlist(
            watchlists.stream().map(Watchlist::id).toList());
        Map<Long, TradingDay> tradingDays = new LinkedHashMap<>();
        items.values().forEach(assets -> assets.forEach(asset ->
            tradingDays.putIfAbsent(asset.id(), TradingDay.of(asset.assetType(), asset.market()))));
        Map<Long, DailyQuote> quotes = tradingDays.isEmpty() ? Map.of() : marketDataFacade.findDailyQuotes(tradingDays);

        return watchlists.stream()
            .map(watchlist -> new WatchlistDto(
                watchlist.uuid(),
                watchlist.name(),
                items.getOrDefault(watchlist.id(), List.of()).stream()
                    .map(asset -> AssetQuoteMapper.toDto(asset, quotes.get(asset.id())))
                    .toList()))
            .toList();
    }
}
