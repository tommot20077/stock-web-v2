package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.asset.service.AssetQueryService;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
    private static final int MAX_PAGE = 10_000;

    private final AssetQueryService assetQueryService;

    public AssetController(AssetQueryService assetQueryService) {
        this.assetQueryService = assetQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AssetDto>> search(
        @RequestParam(defaultValue = "") String query,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.min(Math.max(1, size), 100);
        return ApiResponse.success(assetQueryService.search(query, safePage, safeSize), meta());
    }

    private ApiMeta meta() {
        return new ApiMeta(TraceIdFilter.currentTraceId(), OffsetDateTime.now());
    }
}
