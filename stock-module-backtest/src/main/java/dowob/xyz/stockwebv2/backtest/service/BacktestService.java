package dowob.xyz.stockwebv2.backtest.service;

import dowob.xyz.stockwebv2.backtest.api.BacktestResultDto;
import dowob.xyz.stockwebv2.backtest.api.BacktestRunDto;
import dowob.xyz.stockwebv2.backtest.api.CreateBacktestRunRequest;
import dowob.xyz.stockwebv2.backtest.api.StrategyValidationDto;
import dowob.xyz.stockwebv2.backtest.api.ValidateStrategyRequest;
import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRunStatus;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import dowob.xyz.stockwebv2.backtest.engine.BacktestEngine;
import dowob.xyz.stockwebv2.backtest.engine.BacktestEngineInput;
import dowob.xyz.stockwebv2.backtest.engine.StrategyValidator;
import dowob.xyz.stockwebv2.backtest.repository.BacktestRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class BacktestService {
    private static final String USD = "USD";
    private static final String BUY_HOLD = "buy_hold";
    private static final String CACHED = "cached";

    private final BacktestRepository repository;
    private final BacktestEngine engine;
    private final BacktestMapper mapper;
    private final StrategyValidator strategyValidator;

    public BacktestService(BacktestRepository repository, BacktestEngine engine, BacktestMapper mapper) {
        this(repository, engine, mapper, new StrategyValidator());
    }

    @Autowired
    public BacktestService(BacktestRepository repository, BacktestEngine engine, BacktestMapper mapper, StrategyValidator strategyValidator) {
        this.repository = repository;
        this.engine = engine;
        this.mapper = mapper;
        this.strategyValidator = strategyValidator;
    }

    public BacktestRunDto createRun(Long userId, CreateBacktestRunRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        BacktestStrategyId strategy = parseStrategy(request.strategyId());
        BacktestPeriod period = parsePeriod(request.period());
        validateCapital(request.initialCapital());
        validateRequestOptions(request);

        String symbol = normalizeRequiredSymbol(request.symbol());
        if (!repository.activeSymbolExists(symbol)) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL, ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL.defaultMessage());
        }

        if (strategy == BacktestStrategyId.CUSTOM) {
            validateStrategyCode(request.strategyCode());
        }
        String strategyCode = strategyCodeFor(strategy, request.strategyCode());

        BacktestEngineInput input = new BacktestEngineInput(
            userId,
            strategy,
            symbol,
            period,
            request.initialCapital(),
            strategyCode
        );
        BacktestResult result;
        try {
            result = engine.run(input);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED, exception.getMessage());
        }
        OffsetDateTime now = OffsetDateTime.now();
        BacktestRun run = new BacktestRun(
            null,
            UUID.randomUUID(),
            userId,
            null,
            strategy,
            strategy.label(),
            strategyCode,
            symbol,
            period,
            request.initialCapital(),
            request.currency(),
            request.benchmark(),
            request.dataMode(),
            BacktestRunStatus.SUCCEEDED,
            BigDecimal.ONE,
            null,
            null,
            now,
            now,
            now
        );

        return mapper.toRunDto(repository.createSucceededRun(run, result));
    }

    public StrategyValidationDto validateStrategy(ValidateStrategyRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "request is required");
        }
        return validateStrategyCode(request.strategyCode());
    }

    public BacktestRunDto getRun(Long userId, String runId) {
        return mapper.toRunDto(findRun(userId, runId));
    }

    public BacktestResultDto getResult(Long userId, String runId) {
        BacktestRun run = findRun(userId, runId);
        BacktestResult result = repository.findResultForUser(userId, runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BACKTEST_RESULT_NOT_READY, ErrorCode.BACKTEST_RESULT_NOT_READY.defaultMessage()));
        return mapper.toResultDto(run, result);
    }

    public PageResponse<BacktestRunDto> listRuns(Long userId, String symbol, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String safeSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim();
        PageResponse<BacktestRun> runs = repository.listRuns(userId, safeSymbol, safePage, safeSize);
        return PageResponse.of(
            runs.items().stream().map(mapper::toRunDto).toList(),
            runs.page(),
            runs.size(),
            runs.totalElements()
        );
    }

    private BacktestRun findRun(Long userId, String runId) {
        return repository.findRunForUser(userId, runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, ErrorCode.BACKTEST_RUN_NOT_FOUND.defaultMessage()));
    }

    private BacktestStrategyId parseStrategy(String strategyId) {
        try {
            return BacktestStrategyId.fromApiValue(strategyId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_STRATEGY, ErrorCode.BACKTEST_UNSUPPORTED_STRATEGY.defaultMessage());
        }
    }

    private BacktestPeriod parsePeriod(String period) {
        try {
            return BacktestPeriod.fromApiValue(period);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_PERIOD, ErrorCode.BACKTEST_UNSUPPORTED_PERIOD.defaultMessage());
        }
    }

    private String normalizeRequiredSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "symbol is required");
        }
        return symbol.trim();
    }

    private void validateCapital(BigDecimal initialCapital) {
        if (initialCapital == null || initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL, ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL.defaultMessage());
        }
    }

    private void validateRequestOptions(CreateBacktestRunRequest request) {
        if (!USD.equals(request.currency())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "currency must be USD");
        }
        if (!BUY_HOLD.equals(request.benchmark())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "benchmark must be buy_hold");
        }
        if (!CACHED.equals(request.dataMode())) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_DATA_MODE, ErrorCode.BACKTEST_UNSUPPORTED_DATA_MODE.defaultMessage());
        }
    }

    private StrategyValidationDto validateStrategyCode(String strategyCode) {
        try {
            return strategyValidator.validate(strategyCode);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED, exception.getMessage());
        }
    }

    private String strategyCodeFor(BacktestStrategyId strategy, String strategyCode) {
        return strategy == BacktestStrategyId.CUSTOM ? strategyCode : null;
    }
}
