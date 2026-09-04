package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.backtest.service.BacktestService;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.audit.AuditLogger;
import dowob.xyz.stockwebv2.infrastructure.web.ApiMetaFactory;
import dowob.xyz.stockwebv2.infrastructure.web.AuthenticatedUserResolver;
import dowob.xyz.stockwebv2.infrastructure.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService backtestService;
    private final AuditLogger auditLogger;

    public BacktestController(BacktestService backtestService, AuditLogger auditLogger) {
        this.backtestService = backtestService;
        this.auditLogger = auditLogger;
    }

    /**
     * 建立一次回測。
     *
     * <p>回測會佔用運算資源並產生持久化紀錄，屬於改變系統狀態的寫入操作，因此成功與失敗都留稽核。
     *
     * @param request        建立回測的請求
     * @param authentication 當前請求身份
     * @param servletRequest 用於解析來源 IP
     * @return 建立完成的回測
     */
    @PostMapping("/runs")
    public ApiResponse<BacktestRunDto> createRun(
        @Valid @RequestBody CreateBacktestRunRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        String ip = ClientIpResolver.resolve(servletRequest);
        try {
            BacktestRunDto run = backtestService.createRun(userId, request);
            auditLogger.log(userId, "backtest_create", "backtest:" + run.id(), "success", ip);
            return ApiResponse.success(run, ApiMetaFactory.current());
        } catch (BusinessException exception) {
            auditLogger.log(userId, "backtest_create", "backtest", "failure:" + exception.errorCode().name(), ip);
            throw exception;
        }
    }

    @PostMapping("/strategies/validate")
    public ApiResponse<StrategyValidationDto> validateStrategy(@Valid @RequestBody ValidateStrategyRequest request) {
        return ApiResponse.success(backtestService.validateStrategy(request), ApiMetaFactory.current());
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<BacktestRunDto> getRun(@PathVariable String runId, Authentication authentication) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        return ApiResponse.success(backtestService.getRun(userId, runId), ApiMetaFactory.current());
    }

    @GetMapping("/runs/{runId}/result")
    public ApiResponse<BacktestResultDto> getResult(@PathVariable String runId, Authentication authentication) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        return ApiResponse.success(backtestService.getResult(userId, runId), ApiMetaFactory.current());
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<BacktestRunDto>> listRuns(
        @RequestParam(required = false) String symbol,
        @RequestParam(defaultValue = "0") String page,
        @RequestParam(defaultValue = "20") String size,
        Authentication authentication
    ) {
        Long userId = AuthenticatedUserResolver.resolve(authentication);
        return ApiResponse.success(backtestService.listRuns(
            userId,
            symbol,
            parseQueryInt(page, "page"),
            parseQueryInt(size, "size")
        ), ApiMetaFactory.current());
    }

    private int parseQueryInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " must be a number");
        }
    }
}
