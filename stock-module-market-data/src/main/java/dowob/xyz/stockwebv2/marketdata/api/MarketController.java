package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Market data REST controller — 提供 latest tick 查詢。
 *
 * <p>路徑說明：
 * <ul>
 *   <li>{@code GET /api/v1/market/{symbol}/latest} — 單一資產最新 tick</li>
 *   <li>{@code GET /api/v1/market/latest?symbols=...} — 批次查詢（最多 50）</li>
 * </ul>
 *
 * <p>無路徑衝突：{@link WsTicketController} 使用 {@code /api/v1/market/ws/ticket}，
 * 本 controller 使用 {@code /{symbol}/latest} 與 {@code /latest}，不重疊。
 *
 * @author Yuan
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private final MarketLatestService latestService;

    /**
     * 建構子注入 {@link MarketLatestService}。
     *
     * @param latestService 最新 tick 查詢服務
     */
    public MarketController(MarketLatestService latestService) {
        this.latestService = latestService;
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
        return ApiResponse.success(dto.get(), meta());
    }

    /**
     * 批次取多個 symbol 最新 tick（最多 50）。未知 symbol 從結果略過。
     *
     * @param symbols 資產代號清單（query param {@code symbols}），逗號分隔或多值皆可
     * @return 包含 {@link LatestPriceDto} list 的 {@link ApiResponse}
     */
    @GetMapping("/latest")
    public ApiResponse<List<LatestPriceDto>> latestBatch(@RequestParam("symbols") List<String> symbols) {
        return ApiResponse.success(latestService.findLatestBatch(symbols), meta());
    }

    /**
     * 建立 {@link ApiMeta}，traceId 來自 MDC（由 {@code TraceIdFilter} 寫入）。
     *
     * @return ApiMeta
     */
    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
