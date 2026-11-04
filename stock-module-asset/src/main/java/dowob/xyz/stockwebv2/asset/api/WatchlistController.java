package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.asset.service.WatchlistService;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 觀察清單的 HTTP 端點（WATCH-01 / WATCH-02）。契約見 {@code ai-docs/watchlist-contract.md}。
 *
 * <p>路徑一律以清單 UUID 定位、不放 symbol（FX 代號含「/」）。本 controller 只取出已驗證身分的 userId
 * 轉交 {@link WatchlistService}；擁有權檢查在 service / repository 層（查詢一律綁 user_id）。
 * 非 UUID 格式的 {@code {id}} 由 {@code GlobalExceptionHandler} 的型別錯誤處理回 400 {@code VALIDATION_FAILED}。
 * unsafe method（POST / PUT / PATCH / DELETE）在 cookie 路徑由 {@code BrowserCsrfFilter} 強制 CSRF header。</p>
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

    /**
     * 建立具名清單。
     *
     * @param request        名稱，trim 後 1–30 字
     * @param authentication 已驗證的身分，name 即 userId
     * @return 新建的清單
     */
    @PostMapping
    @PreAuthorize("hasAuthority('WATCHLIST_MANAGE')")
    public ApiResponse<WatchlistDto> create(
        @Valid @RequestBody WatchlistNameRequest request,
        Authentication authentication
    ) {
        return ApiResponse.success(
            watchlistService.create(AuthenticatedUserResolver.resolve(authentication), request.name()),
            ApiMetaFactory.current());
    }

    /**
     * 改名。
     *
     * @param id             清單 UUID
     * @param request        新名稱，trim 後 1–30 字
     * @param authentication 已驗證的身分，name 即 userId
     * @return 改名後的清單（含項目與報價）
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('WATCHLIST_MANAGE')")
    public ApiResponse<WatchlistDto> rename(
        @PathVariable UUID id,
        @Valid @RequestBody WatchlistNameRequest request,
        Authentication authentication
    ) {
        return ApiResponse.success(
            watchlistService.rename(AuthenticatedUserResolver.resolve(authentication), id, request.name()),
            ApiMetaFactory.current());
    }

    /**
     * 刪除清單（項目一併刪除）。
     *
     * @param id             清單 UUID
     * @param authentication 已驗證的身分，name 即 userId
     * @return 無 data 的成功信封
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('WATCHLIST_MANAGE')")
    public ApiResponse<Void> delete(@PathVariable UUID id, Authentication authentication) {
        watchlistService.delete(AuthenticatedUserResolver.resolve(authentication), id);
        return ApiResponse.success(null, ApiMetaFactory.current());
    }

    /**
     * 把資產加到清單尾端；已在清單中則原樣回傳（冪等）。
     *
     * @param id             清單 UUID
     * @param request        資產代號（任何 active 資產，含 FX 與債券）
     * @param authentication 已驗證的身分，name 即 userId
     * @return 加入後的清單（含項目與報價）
     */
    @PostMapping("/{id}/items")
    @PreAuthorize("hasAuthority('WATCHLIST_MANAGE')")
    public ApiResponse<WatchlistDto> addItem(
        @PathVariable UUID id,
        @Valid @RequestBody AddWatchlistItemRequest request,
        Authentication authentication
    ) {
        return ApiResponse.success(
            watchlistService.addItem(AuthenticatedUserResolver.resolve(authentication), id, request.symbol()),
            ApiMetaFactory.current());
    }
}
