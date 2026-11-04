package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.trading.service.ActivityQueryService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 統一活動列表（交易 + 現金股利 + 股數調整）的 HTTP 端點（PORT-13，D-12）。
 *
 * <p>參數與 {@code GET /api/v1/trades} 同名同語意，只有 {@code type} / {@code sort} 的白名單不同；
 * 本 controller 只取出已驗證身分的 userId，把 query string 原樣交給 {@link ActivityQueryService}
 * 解析。唯一的例外是 {@code page} / {@code size}：宣告為 String 再自行轉數字，讓「非數字」走統一的
 * {@code VALIDATION_FAILED} 錯誤信封（附 {@code error.fields}），而不是 Spring 型別轉換的通用 400。
 * {@code GET /api/v1/trades} 與 {@code TradeDto} 不變。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
public class PortfolioActivityController {

    /**
     * 活動列表查詢服務。
     */
    private final ActivityQueryService activityQueryService;

    public PortfolioActivityController(ActivityQueryService activityQueryService) {
        this.activityQueryService = activityQueryService;
    }

    /**
     * 查詢登入者的統一活動列表（唯讀分頁）。
     *
     * <p>日期參數的 {@code '+'} 偏移量必須百分比編碼成 {@code %2B}（理由同 {@code GET /trades}）。</p>
     *
     * @param type           BUY / SELL / DIVIDEND / SHARE_ADJUSTMENT；省略代表全部
     * @param symbol         標的代號；省略代表不依標的篩選
     * @param dateFrom       事件時間下界（含）；省略代表不設下界
     * @param dateTo         事件時間上界（不含）；純日期形式涵蓋當日整天
     * @param sort           排序鍵 executedAt / createdAt / total / quantity；省略代表 executedAt
     * @param direction      排序方向 asc / desc；省略代表 desc
     * @param page           頁碼，預設 0，夾限 0..10000
     * @param size           每頁筆數，預設 20，夾限 1..100
     * @param authentication 已驗證的身分，name 即 userId
     * @return 該頁活動與符合條件的總筆數
     * @throws BusinessException 參數非法（{@code VALIDATION_FAILED}）或標的不存在（{@code ASSET_NOT_FOUND}）時
     */
    @GetMapping("/portfolio/activities")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    public ApiResponse<PageResponse<PortfolioActivityDto>> listActivities(
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String symbol,
        @RequestParam(required = false) String dateFrom,
        @RequestParam(required = false) String dateTo,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String direction,
        @RequestParam(defaultValue = "0") String page,
        @RequestParam(defaultValue = "20") String size,
        Authentication authentication
    ) {
        return ApiResponse.success(activityQueryService.list(
            AuthenticatedUserResolver.resolve(authentication),
            type,
            symbol,
            dateFrom,
            dateTo,
            sort,
            direction,
            parseQueryInt(page, "page"),
            parseQueryInt(size, "size")
        ), ApiMetaFactory.current());
    }

    /**
     * 把 query string 的頁碼 / 筆數轉成整數；訊息與 fields 值皆為靜態字串，不回射原始輸入。
     *
     * @param value 原始字串
     * @param field 參數名，作為 {@code error.fields} 的鍵
     * @return 整數值
     * @throws FieldValidationException 非數字時
     */
    private int parseQueryInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new FieldValidationException(field + " must be a number", Map.of(field, "must be a number"));
        }
    }
}
