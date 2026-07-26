package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
     * @param symbol       資產代號，大小寫敏感
     * @param intervalCode K 線間隔 wire 字串（1m / 5m / 15m / 1h / 1d）
     * @param from         查詢起始時間（含），必填
     * @param to           查詢結束時間（不含），選填
     * @param limit        最大回傳筆數，選填（預設 500，最大 5000）
     * @return 包含 {@link KlineDto} list 的 {@link ApiResponse}
     * @throws BusinessException {@link ErrorCode#KLINE_INTERVAL_INVALID} 若 interval 無效
     */
    @GetMapping("/{symbol}/klines")
    public ApiResponse<List<KlineDto>> klines(
        @PathVariable("symbol") String symbol,
        @RequestParam("interval") String intervalCode,
        @RequestParam("from") Instant from,
        @RequestParam(value = "to", required = false) Instant to,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        KlineInterval interval;
        try {
            interval = KlineInterval.fromCode(intervalCode);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.KLINE_INTERVAL_INVALID,
                "Invalid interval: " + intervalCode + " (valid: 1m/5m/15m/1h/1d)");
        }
        return ApiResponse.success(klineQueryService.findKlines(symbol, interval, from, to, limit), ApiMetaFactory.current());
    }
}
