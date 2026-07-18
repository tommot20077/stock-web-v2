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
import dowob.xyz.stockwebv2.backtest.engine.DeterministicBacktestEngine;
import dowob.xyz.stockwebv2.backtest.engine.StrategyValidator;
import dowob.xyz.stockwebv2.backtest.repository.BacktestRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestServiceTest {
    private static final String VALID_STRATEGY_CODE = "function strategy({ bars }) { return null; }";

    private final InMemoryBacktestRepository repository = new InMemoryBacktestRepository();
    private final InMemoryAssetFacade assetFacade = new InMemoryAssetFacade();
    private final BacktestService service = new BacktestService(
        repository,
        new DeterministicBacktestEngine(new StrategyValidator()),
        new BacktestMapper(),
        new StrategyValidator(),
        assetFacade
    );

    @Test
    void createRunStoresSucceededRun() {
        assetFacade.activeSymbols.add("AAPL");

        BacktestRunDto run = service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null));

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(run.id()).startsWith("bt_");
        assertThat(repository.savedResults).hasSize(1);
    }

    @Test
    void inMemoryAssetFacadeReturnsTradeableSymbolsInAscendingOrder() {
        assetFacade.activeSymbols.add("MSFT");
        assetFacade.activeSymbols.add("AAPL");
        assetFacade.activeSymbols.add("GOOG");

        assertThat(assetFacade.findAllTradeable())
            .extracting(AssetSummary::symbol)
            .containsExactly("AAPL", "GOOG", "MSFT");
    }

    @Test
    void presetStrategyIgnoresSuppliedStrategyCode() {
        assetFacade.activeSymbols.add("AAPL");

        service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null));
        BacktestRunDto withCode = service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), VALID_STRATEGY_CODE));

        assertThat(repository.savedResults.get(1)).isEqualTo(repository.savedResults.getFirst());
        assertThat(repository.runsByExternalId.get(withCode.id()).strategyCode()).isNull();
    }

    @Test
    void createRunRequiresRequest() {
        assertThatThrownBy(() -> service.createRun(1L, null))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception).hasMessage("request is required");
            });
    }

    @Test
    void createRunRequiresNonBlankSymbol() {
        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "  ", "3Y", new BigDecimal("100000"), null)))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception).hasMessage("symbol is required");
            });
    }

    @Test
    void createRunRequiresNonNullSymbol() {
        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", null, "3Y", new BigDecimal("100000"), null)))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception).hasMessage("symbol is required");
            });
    }

    @Test
    void invalidCapitalIsRejected() {
        assetFacade.activeSymbols.add("AAPL");

        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "AAPL", "3Y", BigDecimal.ZERO, null)))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL));
    }

    @Test
    void nullCapitalIsRejected() {
        assetFacade.activeSymbols.add("AAPL");

        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "AAPL", "3Y", null, null)))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL));
    }

    @Test
    void unsupportedSymbolIsRejected() {
        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "NOPE", "3Y", new BigDecimal("100000"), null)))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL));
    }

    @Test
    void inactiveSymbolIsRejected() {
        // symbol 存在於 assets 表但 active = false（已下市）：activeSymbolExists 的 .filter(active) 須將其排除。
        assetFacade.inactiveSymbols.add("AAPL");

        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null)))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL));
    }

    @Test
    void customStrategyRequiresCode() {
        assetFacade.activeSymbols.add("AAPL");

        assertThatThrownBy(() -> service.createRun(1L, request("custom", "AAPL", "3Y", new BigDecimal("100000"), "")))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED));
    }

    @Test
    void engineStrategyValidationFailureMapsToCompileFailed() {
        assetFacade.activeSymbols.add("AAPL");
        BacktestService failingService = new BacktestService(
            repository,
            input -> {
                throw new IllegalArgumentException("engine validator failed");
            },
            new BacktestMapper(),
            new StrategyValidator(),
            assetFacade
        );

        assertThatThrownBy(() -> failingService.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null)))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED);
                assertThat(exception).hasMessage("engine validator failed");
            });
    }

    @Test
    void validateStrategyReturnsValidForValidStrategyFunction() {
        StrategyValidationDto validation = service.validateStrategy(new ValidateStrategyRequest(VALID_STRATEGY_CODE));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.normalizedName()).isEqualTo("strategy");
        assertThat(validation.warnings()).isEmpty();
    }

    @Test
    void validateStrategyRequiresRequest() {
        assertThatThrownBy(() -> service.validateStrategy(null))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception).hasMessage("request is required");
            });
    }

    @Test
    void validateStrategyMapsInvalidCodeToCompileFailed() {
        assertThatThrownBy(() -> service.validateStrategy(new ValidateStrategyRequest("function helper() { return null; }")))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED));
    }

    @Test
    void getRunMapsRepositoryRun() {
        assetFacade.activeSymbols.add("AAPL");
        BacktestRunDto created = service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null));

        BacktestRunDto run = service.getRun(1L, created.id());

        assertThat(run.id()).isEqualTo(created.id());
        assertThat(run.strategyId()).isEqualTo("ma_cross");
        assertThat(run.symbol()).isEqualTo("AAPL");
        assertThat(run.period()).isEqualTo("3Y");
    }

    @Test
    void getRunMapsStoredErrorDetails() {
        BacktestRun seeded = runWithError(UUID.randomUUID(), 1L, "AAPL", "BACKTEST_UNSUPPORTED_SYMBOL", "Unsupported symbol");
        repository.runsByExternalId.put("bt_" + seeded.uuid(), seeded);

        BacktestRunDto run = service.getRun(1L, "bt_" + seeded.uuid());

        assertThat(run.error()).isNotNull();
        assertThat(run.error().code()).isEqualTo("BACKTEST_UNSUPPORTED_SYMBOL");
        assertThat(run.error().message()).isEqualTo("Unsupported symbol");
        assertThat(run.error().fields()).isEmpty();
    }

    @Test
    void getRunMapsMissingRunToNotFound() {
        assertThatThrownBy(() -> service.getRun(1L, "bt_" + UUID.randomUUID()))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_RUN_NOT_FOUND));
    }

    @Test
    void getResultMapsRepositoryRunAndResult() {
        assetFacade.activeSymbols.add("AAPL");
        BacktestRunDto created = service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null));

        BacktestResultDto result = service.getResult(1L, created.id());

        assertThat(result.runId()).isEqualTo(created.id());
        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.equityCurve()).hasSize(12);
        assertThat(result.monthlyReturns()).hasSize(36);
    }

    @Test
    void getResultMapsMissingRunToNotFound() {
        assertThatThrownBy(() -> service.getResult(1L, "bt_" + UUID.randomUUID()))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_RUN_NOT_FOUND));
    }

    @Test
    void getResultMapsMissingResultToNotReady() {
        assetFacade.activeSymbols.add("AAPL");
        BacktestRunDto created = service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null));
        repository.resultsByExternalId.remove(created.id());

        assertThatThrownBy(() -> service.getResult(1L, created.id()))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.BACKTEST_RESULT_NOT_READY));
    }

    @Test
    void listRunsClampsPageAndSizeAndMapsItems() {
        BacktestRun seeded = run(UUID.randomUUID(), 1L, "AAPL");
        repository.runsByExternalId.put("bt_" + seeded.uuid(), seeded);

        PageResponse<BacktestRunDto> page = service.listRuns(1L, "AAPL", -5, 250);

        assertThat(repository.lastPage).isEqualTo(0);
        assertThat(repository.lastSize).isEqualTo(100);
        assertThat(page.page()).isEqualTo(0);
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.items()).extracting(BacktestRunDto::id).containsExactly("bt_" + seeded.uuid());
    }

    @Test
    void listRunsClampsLargePage() {
        BacktestRun seeded = run(UUID.randomUUID(), 1L, "AAPL");
        repository.runsByExternalId.put("bt_" + seeded.uuid(), seeded);

        PageResponse<BacktestRunDto> page = service.listRuns(1L, "AAPL", 100_000, 20);

        assertThat(repository.lastPage).isEqualTo(10_000);
        assertThat(page.page()).isEqualTo(10_000);
    }

    @Test
    void listRunsTrimsSymbolFilter() {
        BacktestRun seeded = run(UUID.randomUUID(), 1L, "AAPL");
        repository.runsByExternalId.put("bt_" + seeded.uuid(), seeded);

        PageResponse<BacktestRunDto> page = service.listRuns(1L, " AAPL ", 0, 20);

        assertThat(repository.lastSymbol).isEqualTo("AAPL");
        assertThat(page.items()).extracting(BacktestRunDto::symbol).containsExactly("AAPL");
    }

    private CreateBacktestRunRequest request(String strategyId, String symbol, String period, BigDecimal initialCapital, String strategyCode) {
        return new CreateBacktestRunRequest(strategyId, strategyCode, symbol, period, initialCapital, "USD", "buy_hold", "cached");
    }

    private BacktestRun run(UUID uuid, Long userId, String symbol) {
        return run(uuid, userId, symbol, BacktestRunStatus.SUCCEEDED, null, null);
    }

    private BacktestRun runWithError(UUID uuid, Long userId, String symbol, String errorCode, String errorMessage) {
        return run(uuid, userId, symbol, BacktestRunStatus.REJECTED, errorCode, errorMessage);
    }

    private BacktestRun run(UUID uuid, Long userId, String symbol, BacktestRunStatus status, String errorCode, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        return new BacktestRun(
            99L,
            uuid,
            userId,
            null,
            BacktestStrategyId.MA_CROSS,
            BacktestStrategyId.MA_CROSS.label(),
            null,
            symbol,
            BacktestPeriod.THREE_YEARS,
            new BigDecimal("100000"),
            "USD",
            "buy_hold",
            "cached",
            status,
            BigDecimal.ONE,
            errorCode,
            errorMessage,
            now,
            now,
            now
        );
    }

    /**
     * AssetFacade 的手寫 fake：以 {@code activeSymbols} 控制哪些 symbol 視為存在且 active，
     * 以 {@code inactiveSymbols} 控制哪些 symbol 存在但 {@code active = false}
     * （symbol 存在於 assets 表但已下市）。
     * backtest 模組僅能經由 Facade 存取 asset，不得直接查 assets 資料表。
     */
    private static final class InMemoryAssetFacade implements AssetFacade {

        private final Set<String> activeSymbols = new HashSet<>();
        private final Set<String> inactiveSymbols = new HashSet<>();

        @Override
        public List<AssetSummary> findAllTradeable() {
            // 遵守 AssetFacade.findAllTradeable() 合約:結果按 symbol 升冪排序,
            // 避免依賴 HashSet 迭代順序而造成 flaky。
            return activeSymbols.stream().sorted().map(symbol -> summaryOf(symbol, true)).toList();
        }

        @Override
        public Optional<AssetSummary> findBySymbol(String symbol) {
            if (activeSymbols.contains(symbol)) {
                return Optional.of(summaryOf(symbol, true));
            }
            if (inactiveSymbols.contains(symbol)) {
                return Optional.of(summaryOf(symbol, false));
            }
            return Optional.empty();
        }

        private static AssetSummary summaryOf(String symbol, boolean active) {
            return new AssetSummary(1L, symbol, symbol, AssetType.STOCK, "US", true, active);
        }
    }

    private static final class InMemoryBacktestRepository implements BacktestRepository {
        private final List<BacktestResult> savedResults = new ArrayList<>();
        private final Map<String, BacktestRun> runsByExternalId = new HashMap<>();
        private final Map<String, BacktestResult> resultsByExternalId = new HashMap<>();
        private String lastSymbol;
        private int lastPage = -1;
        private int lastSize = -1;
        private long nextId = 1L;

        @Override
        public BacktestRun createSucceededRun(BacktestRun run, BacktestResult result) {
            BacktestRun saved = new BacktestRun(
                nextId++,
                run.uuid(),
                run.userId(),
                run.strategyVersionId(),
                run.strategyId(),
                run.strategyLabel(),
                run.strategyCode(),
                run.symbol(),
                run.period(),
                run.initialCapital(),
                run.currency(),
                run.benchmark(),
                run.dataMode(),
                run.status(),
                run.progress(),
                run.errorCode(),
                run.errorMessage(),
                run.createdAt(),
                run.startedAt(),
                run.completedAt()
            );
            String externalId = "bt_" + saved.uuid();
            runsByExternalId.put(externalId, saved);
            resultsByExternalId.put(externalId, result);
            savedResults.add(result);
            return saved;
        }

        @Override
        public Optional<BacktestRun> findRunForUser(Long userId, String externalRunId) {
            return Optional.ofNullable(runsByExternalId.get(externalRunId))
                .filter(run -> run.userId().equals(userId));
        }

        @Override
        public Optional<BacktestResult> findResultForUser(Long userId, String externalRunId) {
            if (findRunForUser(userId, externalRunId).isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(resultsByExternalId.get(externalRunId));
        }

        @Override
        public PageResponse<BacktestRun> listRuns(Long userId, String symbol, int page, int size) {
            lastSymbol = symbol;
            lastPage = page;
            lastSize = size;
            List<BacktestRun> items = runsByExternalId.values().stream()
                .filter(run -> run.userId().equals(userId))
                .filter(run -> symbol == null || symbol.isBlank() || run.symbol().equals(symbol))
                .toList();
            return PageResponse.of(items, page, size, items.size());
        }
    }
}
