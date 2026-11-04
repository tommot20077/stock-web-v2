package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.trading.api.PortfolioActivityDto;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 統一活動列表（PORT-13）的唯讀查詢服務（RED 骨架）。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class ActivityQueryService {

    /**
     * 活動列表的 UNION ALL 查詢。
     */
    private final ActivityRepository repository;

    /**
     * 取得標的代號與交易幣別（trading 不 JOIN 資產主檔，D-21）。
     */
    private final AssetFacade assetFacade;

    public ActivityQueryService(ActivityRepository repository, AssetFacade assetFacade) {
        this.repository = repository;
        this.assetFacade = assetFacade;
    }

    /**
     * 查詢登入者的統一活動列表（RED 骨架：尚未實作）。
     *
     * @param userId    擁有者 id
     * @param type      種類篩選
     * @param symbol    標的代號
     * @param dateFrom  事件時間下界（含）
     * @param dateTo    事件時間上界（不含）
     * @param sort      排序鍵
     * @param direction 排序方向
     * @param page      頁碼
     * @param size      每頁筆數
     * @return 空頁
     */
    public PageResponse<PortfolioActivityDto> list(
        Long userId,
        String type,
        String symbol,
        String dateFrom,
        String dateTo,
        String sort,
        String direction,
        int page,
        int size
    ) {
        return PageResponse.of(List.of(), page, size, 0);
    }
}
