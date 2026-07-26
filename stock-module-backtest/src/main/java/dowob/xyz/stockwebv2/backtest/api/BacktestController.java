package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.backtest.service.BacktestService;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping("/runs")
    public ApiResponse<BacktestRunDto> createRun(
        @Valid @RequestBody CreateBacktestRunRequest request,
        Authentication authentication
    ) {
        Long userId = authenticatedUserId(authentication);
        return ApiResponse.success(backtestService.createRun(userId, request), meta());
    }

    @PostMapping("/strategies/validate")
    public ApiResponse<StrategyValidationDto> validateStrategy(@Valid @RequestBody ValidateStrategyRequest request) {
        return ApiResponse.success(backtestService.validateStrategy(request), meta());
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<BacktestRunDto> getRun(@PathVariable String runId, Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return ApiResponse.success(backtestService.getRun(userId, runId), meta());
    }

    @GetMapping("/runs/{runId}/result")
    public ApiResponse<BacktestResultDto> getResult(@PathVariable String runId, Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        return ApiResponse.success(backtestService.getResult(userId, runId), meta());
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<BacktestRunDto>> listRuns(
        @RequestParam(required = false) String symbol,
        @RequestParam(defaultValue = "0") String page,
        @RequestParam(defaultValue = "20") String size,
        Authentication authentication
    ) {
        Long userId = authenticatedUserId(authentication);
        return ApiResponse.success(backtestService.listRuns(
            userId,
            symbol,
            parseQueryInt(page, "page"),
            parseQueryInt(size, "size")
        ), meta());
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || StringUtils.isBlank(authentication.getName())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
    }

    private int parseQueryInt(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " must be a number");
        }
    }

    private ApiMeta meta() {
        return new ApiMeta(TraceIdFilter.currentTraceId(), OffsetDateTime.now());
    }
}
