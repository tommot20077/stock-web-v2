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
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.apache.commons.lang3.StringUtils;
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
    private static final int MAX_PAGE = 10_000;

    /**
     * 初始資金上限 1e12。與 {@code CreateBacktestRunRequest.initialCapital} 的
     * {@code @DecimalMax("1000000000000")} 同源(Yuan 2026-11-14 裁決,BE-CR-01);
     * 引擎 60 個月最多約 1.31 倍,V3 {@code initial_capital NUMERIC(24,6)} 仍有餘裕。
     * 這裡是縱深防禦:非 HTTP 呼叫端繞過 Bean Validation 時,指數表示的巨大值也不得進入引擎的 {@code setScale}。
     */
    private static final BigDecimal MAX_INITIAL_CAPITAL = new BigDecimal("1000000000000");

    /**
     * 初始資金最多 6 位有效小數。與 {@code @Digits(integer = 13, fraction = 6)} 同源,對齊 V3
     * {@code NUMERIC(24,6)} 與引擎的 {@code setScale(6)};超過 6 位的極小值會被捨入成 0,
     * 造成 drawdown 除以 0。此處以去尾零後的 scale 判斷(尾零不算)。
     */
    private static final int MAX_INITIAL_CAPITAL_SCALE = 6;

    /**
     * 界外初始資金的靜態訊息;不回射輸入值(安全審查 L-1)。
     */
    private static final String INITIAL_CAPITAL_OUT_OF_RANGE_MESSAGE =
        "initialCapital must be at most 1000000000000 with at most 6 decimal places";

    private final BacktestRepository repository;
    private final BacktestEngine engine;
    private final BacktestMapper mapper;
    private final StrategyValidator strategyValidator;
    private final AssetFacade assetFacade;

    public BacktestService(BacktestRepository repository, BacktestEngine engine, BacktestMapper mapper, AssetFacade assetFacade) {
        this(repository, engine, mapper, new StrategyValidator(), assetFacade);
    }

    @Autowired
    public BacktestService(BacktestRepository repository, BacktestEngine engine, BacktestMapper mapper,
                           StrategyValidator strategyValidator, AssetFacade assetFacade) {
        this.repository = repository;
        this.engine = engine;
        this.mapper = mapper;
        this.strategyValidator = strategyValidator;
        this.assetFacade = assetFacade;
    }

    /**
     * 檢查 symbol 是否對應到一個 active 的資產。
     *
     * <p>經由 stock-infrastructure 的 {@link AssetFacade} 查詢，不得直接以 SQL 存取 asset 模組
     * 的資料表（architecture.md §Facade Pattern）。</p>
     *
     * @param symbol 已正規化的資產代號
     * @return 該 symbol 是否存在且為 active
     */
    private boolean activeSymbolExists(String symbol) {
        return assetFacade.findBySymbol(symbol)
            .filter(AssetSummary::active)
            .isPresent();
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
        if (!activeSymbolExists(symbol)) {
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
        int safePage = Math.min(Math.max(0, page), MAX_PAGE);
        int safeSize = Math.max(1, Math.min(100, size));
        String safeSymbol = StringUtils.trimToNull(symbol);
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
        if (StringUtils.isBlank(symbol)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "symbol is required");
        }
        return symbol.trim();
    }

    /**
     * 檢查初始資金:必須大於 0、不超過 {@link #MAX_INITIAL_CAPITAL}、去尾零後最多
     * {@link #MAX_INITIAL_CAPITAL_SCALE} 位小數(BE-CR-01 縱深防禦)。
     *
     * <p>比較順序刻意先做 {@code compareTo}:對 {@code 1e999999999} 這類指數表示是 O(1),
     * 擋下巨大值後才可能進到去尾零;{@code stripTrailingZeros} 也只在原始 scale 已超過上限時才呼叫。
     * 不得先呼叫 {@code setScale} / {@code toPlainString}(會把指數展開成十億位數字)。</p>
     *
     * @param initialCapital 請求的初始資金
     * @throws BusinessException {@code BACKTEST_INVALID_INITIAL_CAPITAL},當值為 null、不大於 0、超過上限或小數位過多
     */
    private void validateCapital(BigDecimal initialCapital) {
        if (initialCapital == null || initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL, ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL.defaultMessage());
        }
        if (initialCapital.compareTo(MAX_INITIAL_CAPITAL) > 0
            || (initialCapital.scale() > MAX_INITIAL_CAPITAL_SCALE
                && initialCapital.stripTrailingZeros().scale() > MAX_INITIAL_CAPITAL_SCALE)) {
            throw new BusinessException(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL, INITIAL_CAPITAL_OUT_OF_RANGE_MESSAGE);
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
