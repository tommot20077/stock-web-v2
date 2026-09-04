package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser;
import dowob.xyz.stockwebv2.common.time.ApiTimeParser.RangeBound;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Market data REST controller — 提供 latest tick 與 K 線查詢。
 *
 * <p>路徑說明：
 * <ul>
 *   <li>{@code GET /api/v1/market/{symbol}/latest} — 單一資產最新 tick</li>
 *   <li>{@code GET /api/v1/market/latest?symbols=...} — 批次查詢（最多 50）</li>
 *   <li>{@code GET /api/v1/market/{symbol}/klines?interval=&from=&to=&limit=} — K 線查詢</li>
 * </ul>
 *
 * <p>無路徑衝突：{@link WsTicketController} 使用 {@code /api/v1/market/ws/ticket}，
 * 本 controller 使用 {@code /{symbol}/latest}、{@code /latest} 與 {@code /{symbol}/klines}，不重疊。
 *
 * @author Yuan
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private final MarketLatestService latestService;
    private final KlineQueryService klineQueryService;

    /**
     * 建構子注入 {@link MarketLatestService} 與 {@link KlineQueryService}。
     *
     * @param latestService    最新 tick 查詢服務
     * @param klineQueryService K 線查詢服務
     */
    public MarketController(MarketLatestService latestService,
                            KlineQueryService klineQueryService) {
        this.latestService = latestService;
        this.klineQueryService = klineQueryService;
    }

    /**
     * 單一 symbol 最新 tick。Redis-first，miss 時 fallback DB。
     *
     * <p>若 symbol 不存在拋 {@code ASSET_NOT_FOUND}（404）；
     * 若 asset 存在但尚無 tick 資料亦拋 {@code ASSET_NOT_FOUND}（預留後續新增專屬 code）。
     *
     * @param symbol 資產代號，大小寫敏感
     * @return 包含 {@link LatestPriceDto} 的 {@link ApiResponse}
     */
    @GetMapping("/{symbol}/latest")
    public ApiResponse<LatestPriceDto> latest(@PathVariable("symbol") String symbol) {
        Optional<LatestPriceDto> dto = latestService.findLatest(symbol);
        if (dto.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSET_NOT_FOUND, "No price data for symbol: " + symbol);
        }
        return ApiResponse.success(dto.get(), ApiMetaFactory.current());
    }

    /**
     * 批次取多個 symbol 最新 tick（最多 50）。未知 symbol 從結果略過。
     *
     * @param symbols 資產代號清單（query param {@code symbols}），逗號分隔或多值皆可
     * @return 包含 {@link LatestPriceDto} list 的 {@link ApiResponse}
     */
    @GetMapping("/latest")
    public ApiResponse<List<LatestPriceDto>> latestBatch(@RequestParam("symbols") List<String> symbols) {
        return ApiResponse.success(latestService.findLatestBatch(symbols), ApiMetaFactory.current());
    }

    /**
     * 查詢指定 symbol 的 K 線資料（continuous aggregate views）。
     *
     * <p>依 {@code interval} 參數路由至對應的 view（1m / 5m / 15m / 1h / 1d）。
     * {@code to} 未指定時預設為當前時間；{@code limit} 未指定時預設 500，最大 5000。
     *
     * <h4>日期參數格式</h4>
     *
     * <p>{@code from} / {@code to} 宣告為 {@code String} 並交由 {@link ApiTimeParser} 解析，
     * <strong>而非</strong>宣告成型別化的 {@code Instant}。原因是型別化繫結在此有兩個問題：</p>
     *
     * <ol>
     *   <li>Servlet 對 query string 採 {@code x-www-form-urlencoded} 解碼規則，會把未經百分比
     *       編碼的 {@code '+'} 解成空白，{@code from=2026-01-01T00:00:00+08:00} 抵達時已變成
     *       {@code 2026-01-01T00:00:00 08:00}。這發生在 Spring 型別轉換<strong>之前</strong>，
     *       所以宣告成 {@code Instant} 不會讓問題消失，只會讓契約上合法的值被拒。</li>
     *   <li>{@code Instant.parse} 只接受帶偏移量的完整時間戳，而 trading 的同類參數接受純日期
     *       與未帶偏移量的形式。同一套 API 對「什麼是合法的時間參數」給兩種答案。</li>
     * </ol>
     *
     * <p>改走 {@link ApiTimeParser#parseRangeBound} 後，本端點與 {@code GET /api/v1/trades}
     * 接受完全相同的三種形式（帶偏移量、未帶偏移量補 UTC、純日期），格式錯誤也回相同的
     * {@link ErrorCode#VALIDATION_FAILED} 錯誤信封。客戶端仍應把 {@code '+'} 正確編碼成
     * {@code %2B}；服務層的還原是防護，不是許可。</p>
     *
     * @param symbol       資產代號，大小寫敏感
     * @param intervalCode K 線間隔 wire 字串（1m / 5m / 15m / 1h / 1d）
     * @param fromRaw      查詢起始時間（含），必填；ISO-8601 日期或時間戳
     * @param toRaw        查詢結束時間（不含），選填；純日期形式涵蓋當日整天
     * @param limit        最大回傳筆數，選填（預設 500，最大 5000）
     * @return 包含 {@link KlineDto} list 的 {@link ApiResponse}
     * @throws BusinessException {@link ErrorCode#KLINE_INTERVAL_INVALID} 若 interval 無效；
     *                           {@link ErrorCode#VALIDATION_FAILED} 若日期格式無法解析
     */
    @GetMapping("/{symbol}/klines")
    public ApiResponse<List<KlineDto>> klines(
        @PathVariable("symbol") String symbol,
        @RequestParam("interval") String intervalCode,
        @RequestParam("from") String fromRaw,
        @RequestParam(value = "to", required = false) String toRaw,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        KlineInterval interval;
        try {
            interval = KlineInterval.fromCode(intervalCode);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.KLINE_INTERVAL_INVALID,
                "Invalid interval: " + intervalCode + " (valid: 1m/5m/15m/1h/1d)");
        }
        Instant from = toInstant(ApiTimeParser.parseRangeBound(fromRaw, "from", RangeBound.LOWER));
        Instant to = toInstant(ApiTimeParser.parseRangeBound(toRaw, "to", RangeBound.UPPER));
        return ApiResponse.success(klineQueryService.findKlines(symbol, interval, from, to, limit), ApiMetaFactory.current());
    }

    /**
     * 把解析結果轉成服務層使用的 {@link Instant}，並保留「未指定」的 null 語意。
     *
     * <p>{@code from} 為 null 時由 {@code KlineQueryService} 拋 {@code from is required}、
     * {@code to} 為 null 時由它預設為當前時間——這兩條語意規則留在服務層，本方法只負責型別轉換。</p>
     *
     * @param value 解析後的時間；null 代表該參數未指定
     * @return 對應的瞬間；未指定時回傳 null
     */
    private static Instant toInstant(OffsetDateTime value) {
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
