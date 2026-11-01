package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.asset.service.WatchlistService;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 觀察清單的 HTTP 端點（WATCH-01）。契約見 {@code ai-docs/watchlist-contract.md}。
 *
 * <p>路徑一律以清單 UUID 定位、不放 symbol（FX 代號含「/」）。本 controller 只取出已驗證身分的 userId
 * 轉交 {@link WatchlistService}；擁有權檢查在 service / repository 層（查詢一律綁 user_id）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /**
     * 列出登入者的全部清單與每檔報價；0 個清單時先建立「我的自選」。
     *
     * @param authentication 已驗證的身分，name 即 userId
     * @return 依 created_at, id 排序的清單
     */
    @GetMapping
    @PreAuthorize("hasAuthority('WATCHLIST_MANAGE')")
    public ApiResponse<List<WatchlistDto>> list(Authentication authentication) {
        return ApiResponse.success(
            watchlistService.list(AuthenticatedUserResolver.resolve(authentication)), ApiMetaFactory.current());
    }
}
