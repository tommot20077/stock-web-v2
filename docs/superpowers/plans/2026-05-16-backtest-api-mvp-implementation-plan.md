# Backtest API MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first authenticated Backtest API slice with normalized PostgreSQL persistence and deterministic result generation so the Vue Backtest page can move from mock mode to API mode.

**Architecture:** Add `stock-module-backtest` as a peer of `stock-module-user` and `stock-module-asset`. The module owns Backtest domain, DTOs, service, deterministic engine, and JDBC persistence; `stock-start` only depends on the module and exposes it through component scanning.

**Tech Stack:** Java 21, Spring Boot 4.0.4, Spring WebMVC, Spring Security, Spring JDBC `JdbcClient`, Flyway, PostgreSQL/Testcontainers, Redis/Testcontainers, JUnit 5, MockMvc.

---

## File Structure

Create:

- `stock-module-backtest/pom.xml`: Maven module dependencies.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/*`: Controller and API DTO records.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/domain/*`: Domain records and enums.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/*`: Deterministic engine and validation helper.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/repository/*`: JDBC persistence and result reconstruction.
- `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/*`: Application service and API mapping.
- `stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngineTest.java`: Engine unit tests.
- `stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/service/BacktestServiceTest.java`: Service unit tests with fake repositories.
- `stock-db-migration/src/main/resources/db/migration/V3__backtest_schema.sql`: Normalized Backtest tables.
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestApiIT.java`: Authenticated API integration tests.
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestPersistenceIT.java`: Migration and readback integration tests.
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/BacktestE2E.java`: End-to-end Backtest flow.

Modify:

- `pom.xml`: Add `<module>stock-module-backtest</module>` before `stock-start`.
- `stock-start/pom.xml`: Add dependency on `stock-module-backtest`.
- `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`: Add Backtest error codes.
- `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/DatabaseCleaner.java`: Clean Backtest rows before `users`.

Do not modify:

- `SecurityConfig` authorization rules. Backtest endpoints are protected by existing `anyRequest().authenticated()`.
- Frontend adapter in this backend plan. Frontend adjustment is a follow-up after backend contract exists.

---

### Task 1: Wire Module, Error Codes, and Backtest Schema

**Files:**
- Modify: `pom.xml`
- Create: `stock-module-backtest/pom.xml`
- Modify: `stock-start/pom.xml`
- Modify: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`
- Create: `stock-db-migration/src/main/resources/db/migration/V3__backtest_schema.sql`
- Test: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestPersistenceIT.java`

- [ ] **Step 1: Add a failing migration integration test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestPersistenceIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestPersistenceIT extends ContainerIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void backtestTablesAreCreatedByFlyway() {
        Integer tableCount = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = 'public'
              and table_name in (
                'backtest_runs',
                'backtest_kpis',
                'backtest_equity_points',
                'backtest_drawdown_points',
                'backtest_monthly_returns',
                'backtest_trades'
              )
            """, Integer.class);

        assertThat(tableCount).isEqualTo(6);
    }
}
```

- [ ] **Step 2: Run the failing migration test**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true" "-Dit.test=BacktestPersistenceIT"
```

Expected: FAIL because the six Backtest tables do not exist.

- [ ] **Step 3: Add the Maven module to the root reactor**

Modify root `pom.xml` modules:

```xml
    <modules>
        <module>stock-common</module>
        <module>stock-db-migration</module>
        <module>stock-infrastructure</module>
        <module>stock-module-user</module>
        <module>stock-module-asset</module>
        <module>stock-module-backtest</module>
        <module>stock-start</module>
    </modules>
```

- [ ] **Step 4: Create `stock-module-backtest/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-module-backtest</artifactId>
    <dependencies>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-infrastructure</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: Add `stock-module-backtest` to `stock-start/pom.xml`**

Insert after `stock-module-asset`:

```xml
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-module-backtest</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 6: Add Backtest error codes**

Modify `ErrorCode.java` so the enum values include:

```java
    BACKTEST_INVALID_INITIAL_CAPITAL(400, "Initial capital must be greater than 0"),
    BACKTEST_UNSUPPORTED_SYMBOL(400, "Unsupported symbol"),
    BACKTEST_UNSUPPORTED_PERIOD(400, "Unsupported backtest period"),
    BACKTEST_UNSUPPORTED_STRATEGY(400, "Unsupported backtest strategy"),
    BACKTEST_UNSUPPORTED_DATA_MODE(400, "Unsupported backtest data mode"),
    BACKTEST_STRATEGY_COMPILE_FAILED(400, "Strategy compile failed"),
    BACKTEST_RUN_NOT_FOUND(404, "Backtest run not found"),
    BACKTEST_RESULT_NOT_READY(409, "Backtest result is not ready"),
```

Place them before `INTERNAL_ERROR` and keep comma syntax valid.

- [ ] **Step 7: Create `V3__backtest_schema.sql`**

```sql
CREATE TABLE backtest_runs (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    strategy_version_id BIGINT,
    strategy_id VARCHAR(32) NOT NULL,
    strategy_label VARCHAR(128) NOT NULL,
    strategy_code TEXT,
    symbol VARCHAR(50) NOT NULL,
    period VARCHAR(8) NOT NULL,
    initial_capital NUMERIC(24, 6) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    benchmark VARCHAR(32) NOT NULL,
    data_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress NUMERIC(6, 5),
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_backtest_runs_uuid UNIQUE (uuid),
    CONSTRAINT ck_backtest_runs_initial_capital_positive CHECK (initial_capital > 0)
);

CREATE INDEX ix_backtest_runs_user_created ON backtest_runs (user_id, created_at DESC, id DESC);
CREATE INDEX ix_backtest_runs_user_symbol_created ON backtest_runs (user_id, symbol, created_at DESC, id DESC);

CREATE TABLE backtest_kpis (
    run_id BIGINT PRIMARY KEY REFERENCES backtest_runs(id) ON DELETE CASCADE,
    total_return_pct NUMERIC(12, 6) NOT NULL,
    buy_hold_return_pct NUMERIC(12, 6) NOT NULL,
    sharpe NUMERIC(12, 6) NOT NULL,
    cagr_pct NUMERIC(12, 6) NOT NULL,
    max_drawdown_pct NUMERIC(12, 6) NOT NULL,
    drawdown_days INT NOT NULL,
    win_rate_pct NUMERIC(12, 6) NOT NULL,
    trade_count INT NOT NULL,
    profit_factor NUMERIC(12, 6) NOT NULL,
    avg_trade_pct NUMERIC(12, 6) NOT NULL
);

CREATE TABLE backtest_equity_points (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
    point_index INT NOT NULL,
    point_date DATE NOT NULL,
    strategy_value NUMERIC(24, 6) NOT NULL,
    benchmark_value NUMERIC(24, 6) NOT NULL,
    CONSTRAINT uk_backtest_equity_points_run_index UNIQUE (run_id, point_index)
);

CREATE INDEX ix_backtest_equity_points_run_date ON backtest_equity_points (run_id, point_date);

CREATE TABLE backtest_drawdown_points (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
    point_index INT NOT NULL,
    point_date DATE NOT NULL,
    drawdown_pct NUMERIC(12, 6) NOT NULL,
    CONSTRAINT uk_backtest_drawdown_points_run_index UNIQUE (run_id, point_index)
);

CREATE INDEX ix_backtest_drawdown_points_run_date ON backtest_drawdown_points (run_id, point_date);

CREATE TABLE backtest_monthly_returns (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
    return_year INT NOT NULL,
    return_month INT NOT NULL,
    return_pct NUMERIC(12, 6) NOT NULL,
    CONSTRAINT uk_backtest_monthly_returns_run_month UNIQUE (run_id, return_year, return_month)
);

CREATE TABLE backtest_trades (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
    trade_index INT NOT NULL,
    trade_date DATE NOT NULL,
    side VARCHAR(8) NOT NULL,
    entry_price NUMERIC(24, 6) NOT NULL,
    exit_price NUMERIC(24, 6) NOT NULL,
    bars INT NOT NULL,
    pnl NUMERIC(24, 6) NOT NULL,
    pnl_pct NUMERIC(12, 6) NOT NULL,
    CONSTRAINT uk_backtest_trades_run_index UNIQUE (run_id, trade_index)
);

CREATE INDEX ix_backtest_trades_run_date ON backtest_trades (run_id, trade_date);
```

- [ ] **Step 8: Verify migration passes**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true" "-Dit.test=BacktestPersistenceIT"
```

Expected: PASS, `BacktestPersistenceIT` reports one test passed.

- [ ] **Step 9: Commit Task 1**

```powershell
git add pom.xml stock-module-backtest/pom.xml stock-start/pom.xml stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java stock-db-migration/src/main/resources/db/migration/V3__backtest_schema.sql stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestPersistenceIT.java
git commit -m "feat: add backtest module and schema"
```

---

### Task 2: Add Domain and API DTO Contracts

**Files:**
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/domain/BacktestRunStatus.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/domain/BacktestStrategyId.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/domain/BacktestPeriod.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/domain/BacktestRun.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/domain/BacktestResult.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/*.java`
- Test: `stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/domain/BacktestContractTest.java`

- [ ] **Step 1: Write failing domain contract tests**

Create `BacktestContractTest.java`:

```java
package dowob.xyz.stockwebv2.backtest.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestContractTest {

    @Test
    void strategyIdsParseApiValues() {
        assertThat(BacktestStrategyId.fromApiValue("ma_cross")).isEqualTo(BacktestStrategyId.MA_CROSS);
        assertThat(BacktestStrategyId.fromApiValue("rsi")).isEqualTo(BacktestStrategyId.RSI);
        assertThat(BacktestStrategyId.fromApiValue("momentum")).isEqualTo(BacktestStrategyId.MOMENTUM);
        assertThat(BacktestStrategyId.fromApiValue("dca")).isEqualTo(BacktestStrategyId.DCA);
        assertThat(BacktestStrategyId.fromApiValue("custom")).isEqualTo(BacktestStrategyId.CUSTOM);
    }

    @Test
    void periodsParseApiValuesAndExposeMonthCounts() {
        assertThat(BacktestPeriod.fromApiValue("1Y").monthCount()).isEqualTo(12);
        assertThat(BacktestPeriod.fromApiValue("3Y").monthCount()).isEqualTo(36);
        assertThat(BacktestPeriod.fromApiValue("5Y").monthCount()).isEqualTo(60);
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> BacktestStrategyId.fromApiValue("other"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BacktestPeriod.fromApiValue("10Y"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the failing contract tests**

Run:

```powershell
.\mvnw.cmd -pl stock-module-backtest test "-Dtest=BacktestContractTest"
```

Expected: FAIL because domain classes do not exist.

- [ ] **Step 3: Add enums**

Create `BacktestStrategyId.java`:

```java
package dowob.xyz.stockwebv2.backtest.domain;

import java.util.Arrays;

public enum BacktestStrategyId {
    MA_CROSS("ma_cross", "MA Cross (20/50)"),
    RSI("rsi", "RSI Mean Reversion"),
    MOMENTUM("momentum", "Momentum (3M)"),
    DCA("dca", "DCA Weekly"),
    CUSTOM("custom", "Custom JS");

    private final String apiValue;
    private final String label;

    BacktestStrategyId(String apiValue, String label) {
        this.apiValue = apiValue;
        this.label = label;
    }

    public String apiValue() {
        return apiValue;
    }

    public String label() {
        return label;
    }

    public static BacktestStrategyId fromApiValue(String value) {
        return Arrays.stream(values())
            .filter(strategy -> strategy.apiValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported strategy: " + value));
    }
}
```

Create `BacktestPeriod.java`:

```java
package dowob.xyz.stockwebv2.backtest.domain;

import java.util.Arrays;

public enum BacktestPeriod {
    ONE_YEAR("1Y", 12),
    THREE_YEARS("3Y", 36),
    FIVE_YEARS("5Y", 60);

    private final String apiValue;
    private final int monthCount;

    BacktestPeriod(String apiValue, int monthCount) {
        this.apiValue = apiValue;
        this.monthCount = monthCount;
    }

    public String apiValue() {
        return apiValue;
    }

    public int monthCount() {
        return monthCount;
    }

    public static BacktestPeriod fromApiValue(String value) {
        return Arrays.stream(values())
            .filter(period -> period.apiValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported period: " + value));
    }
}
```

Create `BacktestRunStatus.java`:

```java
package dowob.xyz.stockwebv2.backtest.domain;

public enum BacktestRunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REJECTED("rejected");

    private final String apiValue;

    BacktestRunStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
```

- [ ] **Step 4: Add domain records**

Create `BacktestRun.java`:

```java
package dowob.xyz.stockwebv2.backtest.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BacktestRun(
    Long id,
    UUID uuid,
    Long userId,
    Long strategyVersionId,
    BacktestStrategyId strategyId,
    String strategyLabel,
    String strategyCode,
    String symbol,
    BacktestPeriod period,
    BigDecimal initialCapital,
    String currency,
    String benchmark,
    String dataMode,
    BacktestRunStatus status,
    BigDecimal progress,
    String errorCode,
    String errorMessage,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt
) {
}
```

Create `BacktestResult.java` with nested records:

```java
package dowob.xyz.stockwebv2.backtest.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResult(
    BacktestKpis kpis,
    List<EquityPoint> equityCurve,
    List<MonthlyReturn> monthlyReturns,
    List<DrawdownPoint> drawdownCurve,
    List<BacktestTrade> trades
) {
    public BacktestResult {
        equityCurve = List.copyOf(equityCurve);
        monthlyReturns = List.copyOf(monthlyReturns);
        drawdownCurve = List.copyOf(drawdownCurve);
        trades = List.copyOf(trades);
    }

    public record BacktestKpis(
        BigDecimal totalReturnPct,
        BigDecimal buyHoldReturnPct,
        BigDecimal sharpe,
        BigDecimal cagrPct,
        BigDecimal maxDrawdownPct,
        int drawdownDays,
        BigDecimal winRatePct,
        int tradeCount,
        BigDecimal profitFactor,
        BigDecimal avgTradePct
    ) {
    }

    public record EquityPoint(int pointIndex, LocalDate date, BigDecimal strategy, BigDecimal benchmark) {
    }

    public record MonthlyReturn(int year, int month, BigDecimal returnPct) {
    }

    public record DrawdownPoint(int pointIndex, LocalDate date, BigDecimal drawdownPct) {
    }

    public record BacktestTrade(
        int tradeIndex,
        LocalDate date,
        String side,
        BigDecimal entry,
        BigDecimal exit,
        int bars,
        BigDecimal pnl,
        BigDecimal pnlPct
    ) {
    }
}
```

- [ ] **Step 5: Add API DTO records**

Create DTOs under `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/`:

```java
package dowob.xyz.stockwebv2.backtest.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateBacktestRunRequest(
    @NotBlank String strategyId,
    String strategyCode,
    @NotBlank String symbol,
    @NotBlank String period,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal initialCapital,
    @NotBlank String currency,
    @NotBlank String benchmark,
    @NotBlank String dataMode
) {
}
```

```java
package dowob.xyz.stockwebv2.backtest.api;

import jakarta.validation.constraints.NotBlank;

public record ValidateStrategyRequest(@NotBlank String strategyCode) {
}
```

```java
package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.common.api.ApiError;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BacktestRunDto(
    String id,
    String strategyId,
    String label,
    String symbol,
    String period,
    BigDecimal initialCapital,
    String currency,
    String status,
    BigDecimal progress,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    ApiError error
) {
}
```

```java
package dowob.xyz.stockwebv2.backtest.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResultDto(
    String runId,
    String status,
    BacktestKpisDto kpis,
    List<EquityPointDto> equityCurve,
    List<MonthlyReturnDto> monthlyReturns,
    List<DrawdownPointDto> drawdownCurve,
    List<BacktestTradeDto> trades
) {
}

record BacktestKpisDto(
    BigDecimal totalReturnPct,
    BigDecimal buyHoldReturnPct,
    BigDecimal sharpe,
    BigDecimal cagrPct,
    BigDecimal maxDrawdownPct,
    int drawdownDays,
    BigDecimal winRatePct,
    int tradeCount,
    BigDecimal profitFactor,
    BigDecimal avgTradePct
) {
}

record EquityPointDto(LocalDate t, BigDecimal strategy, BigDecimal benchmark) {
}

record MonthlyReturnDto(int year, int month, BigDecimal returnPct) {
}

record DrawdownPointDto(LocalDate t, BigDecimal drawdownPct) {
}

record BacktestTradeDto(
    LocalDate date,
    String side,
    BigDecimal entry,
    BigDecimal exit,
    int bars,
    BigDecimal pnl,
    BigDecimal pnlPct
) {
}
```

```java
package dowob.xyz.stockwebv2.backtest.api;

import java.util.List;

public record StrategyValidationDto(boolean valid, String normalizedName, List<String> warnings) {

    public StrategyValidationDto {
        warnings = List.copyOf(warnings);
    }
}
```

- [ ] **Step 6: Verify contract tests pass**

Run:

```powershell
.\mvnw.cmd -pl stock-module-backtest test "-Dtest=BacktestContractTest"
```

Expected: PASS.

- [ ] **Step 7: Commit Task 2**

```powershell
git add stock-module-backtest/src/main/java stock-module-backtest/src/test/java
git commit -m "feat: add backtest domain contracts"
```

---

### Task 3: Implement Deterministic Backtest Engine

**Files:**
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/BacktestEngine.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/BacktestEngineInput.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngine.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine/StrategyValidator.java`
- Test: `stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/engine/DeterministicBacktestEngineTest.java`

- [ ] **Step 1: Write failing deterministic engine tests**

Create `DeterministicBacktestEngineTest.java`:

```java
package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicBacktestEngineTest {

    private final DeterministicBacktestEngine engine = new DeterministicBacktestEngine();

    @Test
    void sameInputProducesSameResult() {
        BacktestEngineInput input = input(BacktestPeriod.THREE_YEARS);

        BacktestResult first = engine.run(input);
        BacktestResult second = engine.run(input);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void resultContainsFullFrontendData() {
        BacktestResult result = engine.run(input(BacktestPeriod.THREE_YEARS));

        assertThat(result.equityCurve()).hasSize(12);
        assertThat(result.drawdownCurve()).hasSize(12);
        assertThat(result.monthlyReturns()).hasSize(36);
        assertThat(result.trades()).hasSize(12);
        assertThat(result.kpis().tradeCount()).isEqualTo(12);
    }

    @Test
    void periodControlsMonthlyReturnCount() {
        assertThat(engine.run(input(BacktestPeriod.ONE_YEAR)).monthlyReturns()).hasSize(12);
        assertThat(engine.run(input(BacktestPeriod.THREE_YEARS)).monthlyReturns()).hasSize(36);
        assertThat(engine.run(input(BacktestPeriod.FIVE_YEARS)).monthlyReturns()).hasSize(60);
    }

    @Test
    void strategyValidatorAcceptsNamedStrategyFunction() {
        StrategyValidator validator = new StrategyValidator();

        assertThat(validator.validate("function strategy({ bars }) { return null; }").valid()).isTrue();
    }

    @Test
    void strategyValidatorRejectsMissingStrategyFunction() {
        StrategyValidator validator = new StrategyValidator();

        assertThatThrownBy(() -> validator.validate("function other() { return null; }"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strategy");
    }

    private BacktestEngineInput input(BacktestPeriod period) {
        return new BacktestEngineInput(
            1L,
            BacktestStrategyId.MA_CROSS,
            "AAPL",
            period,
            new BigDecimal("100000"),
            null
        );
    }
}
```

- [ ] **Step 2: Run failing engine tests**

Run:

```powershell
.\mvnw.cmd -pl stock-module-backtest test "-Dtest=DeterministicBacktestEngineTest"
```

Expected: FAIL because engine classes do not exist.

- [ ] **Step 3: Add engine input and interface**

Create `BacktestEngineInput.java`:

```java
package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;

import java.math.BigDecimal;

public record BacktestEngineInput(
    Long userId,
    BacktestStrategyId strategyId,
    String symbol,
    BacktestPeriod period,
    BigDecimal initialCapital,
    String strategyCode
) {
}
```

Create `BacktestEngine.java`:

```java
package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;

public interface BacktestEngine {
    BacktestResult run(BacktestEngineInput input);
}
```

- [ ] **Step 4: Add strategy validator**

Create `StrategyValidator.java`:

```java
package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.api.StrategyValidationDto;

import java.util.List;

public class StrategyValidator {

    public StrategyValidationDto validate(String code) {
        String source = code == null ? "" : code.trim();
        if (source.isBlank()) {
            throw new IllegalArgumentException("strategyCode is required");
        }
        if (!source.contains("function strategy")) {
            throw new IllegalArgumentException("strategy() function is required");
        }
        if (!balanced(source, '(', ')') || !balanced(source, '{', '}')) {
            throw new IllegalArgumentException("strategyCode has unbalanced delimiters");
        }
        return new StrategyValidationDto(true, "strategy", List.of());
    }

    private boolean balanced(String source, char open, char close) {
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == open) {
                depth++;
            }
            if (c == close) {
                depth--;
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }
}
```

- [ ] **Step 5: Add deterministic engine**

Create `DeterministicBacktestEngine.java`:

```java
package dowob.xyz.stockwebv2.backtest.engine;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class DeterministicBacktestEngine implements BacktestEngine {

    @Override
    public BacktestResult run(BacktestEngineInput input) {
        int seed = Math.abs(Objects.hash(
            input.userId(),
            input.strategyId().apiValue(),
            input.symbol(),
            input.period().apiValue(),
            input.initialCapital().setScale(2, RoundingMode.HALF_UP).toPlainString(),
            input.strategyCode() == null ? 0 : input.strategyCode().hashCode()
        ));
        BigDecimal totalReturn = decimal(30 + seed % 60);
        BigDecimal buyHoldReturn = decimal(totalReturn.doubleValue() * 0.6 + seed % 20);
        BacktestResult.BacktestKpis kpis = new BacktestResult.BacktestKpis(
            totalReturn,
            buyHoldReturn,
            decimal(0.8 + (seed % 18) / 10.0),
            decimal(totalReturn.doubleValue() / 3.0),
            decimal(-(8 + seed % 18)),
            30 + seed % 90,
            decimal(48 + seed % 30),
            12,
            decimal(1.1 + (seed % 22) / 10.0),
            decimal(0.4 + (seed % 30) / 10.0)
        );
        return new BacktestResult(
            kpis,
            equity(input.initialCapital(), totalReturn),
            monthly(input.period().monthCount(), seed),
            drawdown(seed),
            trades(seed)
        );
    }

    private List<BacktestResult.EquityPoint> equity(BigDecimal initialCapital, BigDecimal totalReturnPct) {
        List<BacktestResult.EquityPoint> points = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 12; i++) {
            BigDecimal strategy = initialCapital.multiply(decimal(1 + ((i + 1) * totalReturnPct.doubleValue() / 1200.0)));
            BigDecimal benchmark = initialCapital.multiply(decimal(1 + ((i + 1) * totalReturnPct.doubleValue() / 1500.0)));
            points.add(new BacktestResult.EquityPoint(i, start.plusMonths(i), money(strategy), money(benchmark)));
        }
        return points;
    }

    private List<BacktestResult.MonthlyReturn> monthly(int monthCount, int seed) {
        List<BacktestResult.MonthlyReturn> returns = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1).minusMonths(monthCount - 1L);
        for (int i = 0; i < monthCount; i++) {
            LocalDate date = start.plusMonths(i);
            returns.add(new BacktestResult.MonthlyReturn(date.getYear(), date.getMonthValue(), decimal(Math.sin(seed + i) * 4.0)));
        }
        return returns;
    }

    private List<BacktestResult.DrawdownPoint> drawdown(int seed) {
        List<BacktestResult.DrawdownPoint> points = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 12; i++) {
            points.add(new BacktestResult.DrawdownPoint(i, start.plusMonths(i), decimal(-Math.abs(Math.sin(seed + i) * 10.0))));
        }
        return points;
    }

    private List<BacktestResult.BacktestTrade> trades(int seed) {
        List<BacktestResult.BacktestTrade> trades = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double wave = Math.sin(seed + i);
            BigDecimal entry = decimal(100 + i * 3.0);
            BigDecimal exit = decimal(104 + i * 4.0);
            BigDecimal pnl = money(exit.subtract(entry).multiply(new BigDecimal("100")));
            trades.add(new BacktestResult.BacktestTrade(
                i,
                LocalDate.of(2026, ((i % 12) + 1), 12),
                wave >= 0 ? "BUY" : "SELL",
                entry,
                exit,
                5 + i,
                pnl,
                decimal(pnl.doubleValue() / 1000.0)
            ));
        }
        return trades;
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 6: Run engine tests**

Run:

```powershell
.\mvnw.cmd -pl stock-module-backtest test "-Dtest=DeterministicBacktestEngineTest"
```

Expected: PASS.

- [ ] **Step 7: Commit Task 3**

```powershell
git add stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/engine stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/engine
git commit -m "feat: add deterministic backtest engine"
```

---

### Task 4: Implement JDBC Persistence and Readback

**Files:**
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/repository/BacktestRepository.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/repository/JdbcBacktestRepository.java`
- Modify: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestPersistenceIT.java`

- [ ] **Step 1: Extend persistence test to insert and reconstruct result**

Append this test to `BacktestPersistenceIT`:

```java
    @Test
    void persistsAndReadsBackNormalizedBacktestResult() {
        Long userId = createUser("backtest-persist@example.com", "backtestpersist");
        JdbcBacktestRepository repository = new JdbcBacktestRepository(jdbcTemplate.getDataSource());
        OffsetDateTime now = OffsetDateTime.parse("2026-05-16T01:30:00Z");
        BacktestRun run = new BacktestRun(
            null,
            UUID.randomUUID(),
            userId,
            null,
            BacktestStrategyId.MA_CROSS,
            "MA Cross (20/50)",
            null,
            "AAPL",
            BacktestPeriod.THREE_YEARS,
            new BigDecimal("100000"),
            "USD",
            "buy_hold",
            "cached",
            BacktestRunStatus.SUCCEEDED,
            BigDecimal.ONE,
            null,
            null,
            now,
            now,
            now
        );
        BacktestResult result = new DeterministicBacktestEngine().run(new BacktestEngineInput(
            userId,
            BacktestStrategyId.MA_CROSS,
            "AAPL",
            BacktestPeriod.THREE_YEARS,
            new BigDecimal("100000"),
            null
        ));

        BacktestRun saved = repository.createSucceededRun(run, result);

        assertThat(repository.findRunForUser(userId, "bt_" + saved.uuid()).orElseThrow().symbol()).isEqualTo("AAPL");
        assertThat(repository.findResultForUser(userId, "bt_" + saved.uuid()).orElseThrow().trades()).hasSize(12);
    }
```

Also add helper method:

```java
    private Long createUser(String email, String username) {
        return jdbcTemplate.queryForObject("""
            insert into users (email, username, password_hash, role, status, token_version)
            values (?, ?, 'hash', 'USER', 'ACTIVE', 1)
            returning id
            """, Long.class, email, username);
    }
```

Add imports for repository/domain/engine classes.

- [ ] **Step 2: Run failing persistence readback test**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true" "-Dit.test=BacktestPersistenceIT"
```

Expected: FAIL because repository classes do not exist.

- [ ] **Step 3: Add repository interface**

Create `BacktestRepository.java`:

```java
package dowob.xyz.stockwebv2.backtest.repository;

import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.common.api.PageResponse;

import java.util.Optional;

public interface BacktestRepository {
    boolean activeSymbolExists(String symbol);
    BacktestRun createSucceededRun(BacktestRun run, BacktestResult result);
    Optional<BacktestRun> findRunForUser(Long userId, String externalRunId);
    Optional<BacktestResult> findResultForUser(Long userId, String externalRunId);
    PageResponse<BacktestRun> listRuns(Long userId, String symbol, int page, int size);
}
```

- [ ] **Step 4: Add `JdbcBacktestRepository` constructor pattern**

Use `JdbcClient.create(dataSource)` so tests can instantiate with the `DataSource`:

```java
package dowob.xyz.stockwebv2.backtest.repository;

import dowob.xyz.stockwebv2.backtest.domain.BacktestPeriod;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRunStatus;
import dowob.xyz.stockwebv2.backtest.domain.BacktestStrategyId;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBacktestRepository implements BacktestRepository {
    private static final String RUN_COLUMNS = """
        id, uuid, user_id, strategy_version_id, strategy_id, strategy_label, strategy_code,
        symbol, period, initial_capital, currency, benchmark, data_mode, status, progress,
        error_code, error_message, created_at, started_at, completed_at
        """;

    private final JdbcClient jdbcClient;

    public JdbcBacktestRepository(DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }
}
```

- [ ] **Step 5: Implement symbol check and external id parsing**

Add methods inside `JdbcBacktestRepository`:

```java
    @Override
    public boolean activeSymbolExists(String symbol) {
        Long count = jdbcClient.sql("select count(*) from assets where active = true and symbol = :symbol")
            .param("symbol", symbol)
            .query(Long.class)
            .single();
        return count != null && count > 0;
    }

    private UUID parseExternalRunId(String externalRunId) {
        if (externalRunId == null || !externalRunId.startsWith("bt_")) {
            throw new IllegalArgumentException("Invalid backtest run id");
        }
        return UUID.fromString(externalRunId.substring(3));
    }

    private String externalRunId(UUID uuid) {
        return "bt_" + uuid;
    }
```

- [ ] **Step 6: Implement run insert and child inserts**

Add `createSucceededRun` and helper insert methods. Keep all inserts in one transaction:

```java
    @Override
    @Transactional
    public BacktestRun createSucceededRun(BacktestRun run, BacktestResult result) {
        BacktestRun saved = jdbcClient.sql("""
                insert into backtest_runs (
                    uuid, user_id, strategy_version_id, strategy_id, strategy_label, strategy_code,
                    symbol, period, initial_capital, currency, benchmark, data_mode, status, progress,
                    error_code, error_message, created_at, started_at, completed_at
                )
                values (
                    :uuid, :userId, :strategyVersionId, :strategyId, :strategyLabel, :strategyCode,
                    :symbol, :period, :initialCapital, :currency, :benchmark, :dataMode, :status, :progress,
                    :errorCode, :errorMessage, :createdAt, :startedAt, :completedAt
                )
                returning """ + RUN_COLUMNS)
            .param("uuid", run.uuid())
            .param("userId", run.userId())
            .param("strategyVersionId", run.strategyVersionId())
            .param("strategyId", run.strategyId().apiValue())
            .param("strategyLabel", run.strategyLabel())
            .param("strategyCode", run.strategyCode())
            .param("symbol", run.symbol())
            .param("period", run.period().apiValue())
            .param("initialCapital", run.initialCapital())
            .param("currency", run.currency())
            .param("benchmark", run.benchmark())
            .param("dataMode", run.dataMode())
            .param("status", run.status().apiValue())
            .param("progress", run.progress())
            .param("errorCode", run.errorCode())
            .param("errorMessage", run.errorMessage())
            .param("createdAt", run.createdAt())
            .param("startedAt", run.startedAt())
            .param("completedAt", run.completedAt())
            .query(this::mapRun)
            .single();

        insertKpis(saved.id(), result.kpis());
        result.equityCurve().forEach(point -> insertEquityPoint(saved.id(), point));
        result.drawdownCurve().forEach(point -> insertDrawdownPoint(saved.id(), point));
        result.monthlyReturns().forEach(month -> insertMonthlyReturn(saved.id(), month));
        result.trades().forEach(trade -> insertTrade(saved.id(), trade));
        return saved;
    }
```

Add insert helpers with the same explicit parameter style as `createSucceededRun`:

```java
    private void insertKpis(Long runId, BacktestResult.BacktestKpis kpis) {
        jdbcClient.sql("""
                insert into backtest_kpis (
                    run_id, total_return_pct, buy_hold_return_pct, sharpe, cagr_pct,
                    max_drawdown_pct, drawdown_days, win_rate_pct, trade_count, profit_factor, avg_trade_pct
                )
                values (
                    :runId, :totalReturnPct, :buyHoldReturnPct, :sharpe, :cagrPct,
                    :maxDrawdownPct, :drawdownDays, :winRatePct, :tradeCount, :profitFactor, :avgTradePct
                )
                """)
            .param("runId", runId)
            .param("totalReturnPct", kpis.totalReturnPct())
            .param("buyHoldReturnPct", kpis.buyHoldReturnPct())
            .param("sharpe", kpis.sharpe())
            .param("cagrPct", kpis.cagrPct())
            .param("maxDrawdownPct", kpis.maxDrawdownPct())
            .param("drawdownDays", kpis.drawdownDays())
            .param("winRatePct", kpis.winRatePct())
            .param("tradeCount", kpis.tradeCount())
            .param("profitFactor", kpis.profitFactor())
            .param("avgTradePct", kpis.avgTradePct())
            .update();
    }

    private void insertEquityPoint(Long runId, BacktestResult.EquityPoint point) {
        jdbcClient.sql("""
                insert into backtest_equity_points (run_id, point_index, point_date, strategy_value, benchmark_value)
                values (:runId, :pointIndex, :pointDate, :strategyValue, :benchmarkValue)
                """)
            .param("runId", runId)
            .param("pointIndex", point.pointIndex())
            .param("pointDate", point.date())
            .param("strategyValue", point.strategy())
            .param("benchmarkValue", point.benchmark())
            .update();
    }

    private void insertDrawdownPoint(Long runId, BacktestResult.DrawdownPoint point) {
        jdbcClient.sql("""
                insert into backtest_drawdown_points (run_id, point_index, point_date, drawdown_pct)
                values (:runId, :pointIndex, :pointDate, :drawdownPct)
                """)
            .param("runId", runId)
            .param("pointIndex", point.pointIndex())
            .param("pointDate", point.date())
            .param("drawdownPct", point.drawdownPct())
            .update();
    }

    private void insertMonthlyReturn(Long runId, BacktestResult.MonthlyReturn month) {
        jdbcClient.sql("""
                insert into backtest_monthly_returns (run_id, return_year, return_month, return_pct)
                values (:runId, :returnYear, :returnMonth, :returnPct)
                """)
            .param("runId", runId)
            .param("returnYear", month.year())
            .param("returnMonth", month.month())
            .param("returnPct", month.returnPct())
            .update();
    }

    private void insertTrade(Long runId, BacktestResult.BacktestTrade trade) {
        jdbcClient.sql("""
                insert into backtest_trades (run_id, trade_index, trade_date, side, entry_price, exit_price, bars, pnl, pnl_pct)
                values (:runId, :tradeIndex, :tradeDate, :side, :entryPrice, :exitPrice, :bars, :pnl, :pnlPct)
                """)
            .param("runId", runId)
            .param("tradeIndex", trade.tradeIndex())
            .param("tradeDate", trade.date())
            .param("side", trade.side())
            .param("entryPrice", trade.entry())
            .param("exitPrice", trade.exit())
            .param("bars", trade.bars())
            .param("pnl", trade.pnl())
            .param("pnlPct", trade.pnlPct())
            .update();
    }
```

- [ ] **Step 7: Implement find and list methods**

Add:

```java
    @Override
    public Optional<BacktestRun> findRunForUser(Long userId, String externalRunId) {
        UUID uuid = parseExternalRunId(externalRunId);
        return jdbcClient.sql("select " + RUN_COLUMNS + " from backtest_runs where user_id = :userId and uuid = :uuid")
            .param("userId", userId)
            .param("uuid", uuid)
            .query(this::mapRun)
            .optional();
    }

    @Override
    public PageResponse<BacktestRun> listRuns(Long userId, String symbol, int page, int size) {
        boolean filterSymbol = symbol != null && !symbol.isBlank();
        long offset = (long) page * size;
        String where = filterSymbol ? "where user_id = :userId and symbol = :symbol" : "where user_id = :userId";
        JdbcClient.StatementSpec listSpec = jdbcClient.sql("""
                select """ + RUN_COLUMNS + """
                from backtest_runs
                """ + where + """
                order by created_at desc, id desc
                limit :limit offset :offset
                """)
            .param("userId", userId)
            .param("limit", size)
            .param("offset", offset);
        JdbcClient.StatementSpec countSpec = jdbcClient.sql("select count(*) from backtest_runs " + where)
            .param("userId", userId);
        if (filterSymbol) {
            listSpec = listSpec.param("symbol", symbol);
            countSpec = countSpec.param("symbol", symbol);
        }
        long total = countSpec.query(Long.class).single();
        return PageResponse.of(listSpec.query(this::mapRun).list(), page, size, total);
    }
```

Add `findResultForUser`:

```java
    @Override
    public Optional<BacktestResult> findResultForUser(Long userId, String externalRunId) {
        Optional<BacktestRun> run = findRunForUser(userId, externalRunId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        Long runId = run.get().id();
        BacktestResult.BacktestKpis kpis = jdbcClient.sql("select * from backtest_kpis where run_id = :runId")
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.BacktestKpis(
                rs.getBigDecimal("total_return_pct"),
                rs.getBigDecimal("buy_hold_return_pct"),
                rs.getBigDecimal("sharpe"),
                rs.getBigDecimal("cagr_pct"),
                rs.getBigDecimal("max_drawdown_pct"),
                rs.getInt("drawdown_days"),
                rs.getBigDecimal("win_rate_pct"),
                rs.getInt("trade_count"),
                rs.getBigDecimal("profit_factor"),
                rs.getBigDecimal("avg_trade_pct")
            ))
            .single();
        List<BacktestResult.EquityPoint> equity = jdbcClient.sql("""
                select point_index, point_date, strategy_value, benchmark_value
                from backtest_equity_points
                where run_id = :runId
                order by point_index
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.EquityPoint(
                rs.getInt("point_index"),
                rs.getObject("point_date", LocalDate.class),
                rs.getBigDecimal("strategy_value"),
                rs.getBigDecimal("benchmark_value")
            ))
            .list();
        List<BacktestResult.DrawdownPoint> drawdown = jdbcClient.sql("""
                select point_index, point_date, drawdown_pct
                from backtest_drawdown_points
                where run_id = :runId
                order by point_index
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.DrawdownPoint(
                rs.getInt("point_index"),
                rs.getObject("point_date", LocalDate.class),
                rs.getBigDecimal("drawdown_pct")
            ))
            .list();
        List<BacktestResult.MonthlyReturn> monthly = jdbcClient.sql("""
                select return_year, return_month, return_pct
                from backtest_monthly_returns
                where run_id = :runId
                order by return_year, return_month
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.MonthlyReturn(
                rs.getInt("return_year"),
                rs.getInt("return_month"),
                rs.getBigDecimal("return_pct")
            ))
            .list();
        List<BacktestResult.BacktestTrade> trades = jdbcClient.sql("""
                select trade_index, trade_date, side, entry_price, exit_price, bars, pnl, pnl_pct
                from backtest_trades
                where run_id = :runId
                order by trade_index
                """)
            .param("runId", runId)
            .query((rs, rowNum) -> new BacktestResult.BacktestTrade(
                rs.getInt("trade_index"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("side"),
                rs.getBigDecimal("entry_price"),
                rs.getBigDecimal("exit_price"),
                rs.getInt("bars"),
                rs.getBigDecimal("pnl"),
                rs.getBigDecimal("pnl_pct")
            ))
            .list();
        return Optional.of(new BacktestResult(kpis, equity, monthly, drawdown, trades));
    }
```

- [ ] **Step 8: Implement row mappers**

Add `mapRun`:

```java
    private BacktestRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new BacktestRun(
            rs.getLong("id"),
            rs.getObject("uuid", UUID.class),
            rs.getLong("user_id"),
            rs.getObject("strategy_version_id", Long.class),
            BacktestStrategyId.fromApiValue(rs.getString("strategy_id")),
            rs.getString("strategy_label"),
            rs.getString("strategy_code"),
            rs.getString("symbol"),
            BacktestPeriod.fromApiValue(rs.getString("period")),
            rs.getBigDecimal("initial_capital"),
            rs.getString("currency"),
            rs.getString("benchmark"),
            rs.getString("data_mode"),
            BacktestRunStatus.valueOf(rs.getString("status").toUpperCase()),
            rs.getBigDecimal("progress"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class)
        );
    }
```

The stored status is the lowercase API value. `toUpperCase()` maps `succeeded` to `SUCCEEDED`, which matches `BacktestRunStatus`.

- [ ] **Step 9: Verify persistence tests pass**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true" "-Dit.test=BacktestPersistenceIT"
```

Expected: PASS.

- [ ] **Step 10: Commit Task 4**

```powershell
git add stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/repository stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestPersistenceIT.java
git commit -m "feat: persist backtest results"
```

---

### Task 5: Implement Backtest Service and DTO Mapping

**Files:**
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestService.java`
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service/BacktestMapper.java`
- Test: `stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/service/BacktestServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create `BacktestServiceTest.java` with an in-memory fake repository:

```java
package dowob.xyz.stockwebv2.backtest.service;

import dowob.xyz.stockwebv2.backtest.api.CreateBacktestRunRequest;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import dowob.xyz.stockwebv2.backtest.engine.DeterministicBacktestEngine;
import dowob.xyz.stockwebv2.backtest.repository.BacktestRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestServiceTest {

    private final FakeBacktestRepository repository = new FakeBacktestRepository();
    private final BacktestService service = new BacktestService(repository, new DeterministicBacktestEngine(), new BacktestMapper());

    @Test
    void createRunStoresSucceededRun() {
        repository.symbolExists = true;

        var dto = service.createRun(1L, request("ma_cross", "AAPL", "3Y", new BigDecimal("100000"), null));

        assertThat(dto.status()).isEqualTo("succeeded");
        assertThat(dto.id()).startsWith("bt_");
        assertThat(repository.savedResults).hasSize(1);
    }

    @Test
    void invalidCapitalIsRejected() {
        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "AAPL", "3Y", BigDecimal.ZERO, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL);
    }

    @Test
    void unsupportedSymbolIsRejected() {
        repository.symbolExists = false;

        assertThatThrownBy(() -> service.createRun(1L, request("ma_cross", "NOPE", "3Y", new BigDecimal("100000"), null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL);
    }

    @Test
    void customStrategyRequiresCode() {
        repository.symbolExists = true;

        assertThatThrownBy(() -> service.createRun(1L, request("custom", "AAPL", "3Y", new BigDecimal("100000"), "")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED);
    }

    private CreateBacktestRunRequest request(String strategy, String symbol, String period, BigDecimal capital, String code) {
        return new CreateBacktestRunRequest(strategy, code, symbol, period, capital, "USD", "buy_hold", "cached");
    }

    private static class FakeBacktestRepository implements BacktestRepository {
        boolean symbolExists;
        Map<String, BacktestResult> savedResults = new HashMap<>();

        @Override
        public boolean activeSymbolExists(String symbol) {
            return symbolExists;
        }

        @Override
        public BacktestRun createSucceededRun(BacktestRun run, BacktestResult result) {
            BacktestRun saved = new BacktestRun(
                1L, run.uuid(), run.userId(), run.strategyVersionId(), run.strategyId(), run.strategyLabel(),
                run.strategyCode(), run.symbol(), run.period(), run.initialCapital(), run.currency(),
                run.benchmark(), run.dataMode(), run.status(), run.progress(), run.errorCode(), run.errorMessage(),
                run.createdAt(), run.startedAt(), run.completedAt()
            );
            savedResults.put("bt_" + saved.uuid(), result);
            return saved;
        }

        @Override
        public Optional<BacktestRun> findRunForUser(Long userId, String externalRunId) {
            return Optional.empty();
        }

        @Override
        public Optional<BacktestResult> findResultForUser(Long userId, String externalRunId) {
            return Optional.ofNullable(savedResults.get(externalRunId));
        }

        @Override
        public PageResponse<BacktestRun> listRuns(Long userId, String symbol, int page, int size) {
            return PageResponse.of(java.util.List.of(), page, size, 0);
        }
    }
}
```

- [ ] **Step 2: Run failing service tests**

Run:

```powershell
.\mvnw.cmd -pl stock-module-backtest test "-Dtest=BacktestServiceTest"
```

Expected: FAIL because `BacktestService` and `BacktestMapper` do not exist.

- [ ] **Step 3: Add `BacktestMapper`**

Create mapper methods:

```java
package dowob.xyz.stockwebv2.backtest.service;

import dowob.xyz.stockwebv2.backtest.api.BacktestResultDto;
import dowob.xyz.stockwebv2.backtest.api.BacktestRunDto;
import dowob.xyz.stockwebv2.backtest.domain.BacktestResult;
import dowob.xyz.stockwebv2.backtest.domain.BacktestRun;
import org.springframework.stereotype.Component;

@Component
public class BacktestMapper {

    public BacktestRunDto toRunDto(BacktestRun run) {
        return new BacktestRunDto(
            externalRunId(run),
            run.strategyId().apiValue(),
            run.strategyLabel(),
            run.symbol(),
            run.period().apiValue(),
            run.initialCapital(),
            run.currency(),
            run.status().apiValue(),
            run.progress(),
            run.createdAt(),
            run.startedAt(),
            run.completedAt(),
            null
        );
    }

    public BacktestResultDto toResultDto(BacktestRun run, BacktestResult result) {
        return BacktestResultDto.fromDomain(externalRunId(run), run.status().apiValue(), result);
    }

    private String externalRunId(BacktestRun run) {
        return "bt_" + run.uuid();
    }
}
```

Add `fromDomain` to `BacktestResultDto` and make nested DTO records public:

```java
public record BacktestResultDto(
    String runId,
    String status,
    BacktestKpisDto kpis,
    List<EquityPointDto> equityCurve,
    List<MonthlyReturnDto> monthlyReturns,
    List<DrawdownPointDto> drawdownCurve,
    List<BacktestTradeDto> trades
) {
    public static BacktestResultDto fromDomain(String runId, String status, BacktestResult result) {
        return new BacktestResultDto(
            runId,
            status,
            new BacktestKpisDto(
                result.kpis().totalReturnPct(),
                result.kpis().buyHoldReturnPct(),
                result.kpis().sharpe(),
                result.kpis().cagrPct(),
                result.kpis().maxDrawdownPct(),
                result.kpis().drawdownDays(),
                result.kpis().winRatePct(),
                result.kpis().tradeCount(),
                result.kpis().profitFactor(),
                result.kpis().avgTradePct()
            ),
            result.equityCurve().stream()
                .map(point -> new EquityPointDto(point.date(), point.strategy(), point.benchmark()))
                .toList(),
            result.monthlyReturns().stream()
                .map(month -> new MonthlyReturnDto(month.year(), month.month(), month.returnPct()))
                .toList(),
            result.drawdownCurve().stream()
                .map(point -> new DrawdownPointDto(point.date(), point.drawdownPct()))
                .toList(),
            result.trades().stream()
                .map(trade -> new BacktestTradeDto(
                    trade.date(),
                    trade.side(),
                    trade.entry(),
                    trade.exit(),
                    trade.bars(),
                    trade.pnl(),
                    trade.pnlPct()
                ))
                .toList()
        );
    }
}

public record BacktestKpisDto(
    BigDecimal totalReturnPct,
    BigDecimal buyHoldReturnPct,
    BigDecimal sharpe,
    BigDecimal cagrPct,
    BigDecimal maxDrawdownPct,
    int drawdownDays,
    BigDecimal winRatePct,
    int tradeCount,
    BigDecimal profitFactor,
    BigDecimal avgTradePct
) {
}

public record EquityPointDto(LocalDate t, BigDecimal strategy, BigDecimal benchmark) {
}

public record MonthlyReturnDto(int year, int month, BigDecimal returnPct) {
}

public record DrawdownPointDto(LocalDate t, BigDecimal drawdownPct) {
}

public record BacktestTradeDto(
    LocalDate date,
    String side,
    BigDecimal entry,
    BigDecimal exit,
    int bars,
    BigDecimal pnl,
    BigDecimal pnlPct
) {
}
```

- [ ] **Step 4: Add `BacktestService` validation and create flow**

Create `BacktestService.java`:

```java
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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class BacktestService {
    private final BacktestRepository repository;
    private final BacktestEngine engine;
    private final BacktestMapper mapper;
    private final StrategyValidator strategyValidator = new StrategyValidator();

    public BacktestService(BacktestRepository repository, BacktestEngine engine, BacktestMapper mapper) {
        this.repository = repository;
        this.engine = engine;
        this.mapper = mapper;
    }

    public BacktestRunDto createRun(Long userId, CreateBacktestRunRequest request) {
        BacktestStrategyId strategy = parseStrategy(request.strategyId());
        BacktestPeriod period = parsePeriod(request.period());
        validateCreateRequest(strategy, request);
        BacktestResult result = engine.run(new BacktestEngineInput(
            userId,
            strategy,
            request.symbol().trim(),
            period,
            request.initialCapital(),
            request.strategyCode()
        ));
        OffsetDateTime now = OffsetDateTime.now();
        BacktestRun run = new BacktestRun(
            null,
            UUID.randomUUID(),
            userId,
            null,
            strategy,
            strategy.label(),
            request.strategyCode(),
            request.symbol().trim(),
            period,
            request.initialCapital(),
            "USD",
            "buy_hold",
            "cached",
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
}
```

Add private validation methods:

```java
    private void validateCreateRequest(BacktestStrategyId strategy, CreateBacktestRunRequest request) {
        if (request.initialCapital() == null || request.initialCapital().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL, ErrorCode.BACKTEST_INVALID_INITIAL_CAPITAL.defaultMessage());
        }
        if (!"USD".equals(request.currency())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "currency must be USD");
        }
        if (!"buy_hold".equals(request.benchmark())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "benchmark must be buy_hold");
        }
        if (!"cached".equals(request.dataMode())) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_DATA_MODE, ErrorCode.BACKTEST_UNSUPPORTED_DATA_MODE.defaultMessage());
        }
        if (!repository.activeSymbolExists(request.symbol().trim())) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL, ErrorCode.BACKTEST_UNSUPPORTED_SYMBOL.defaultMessage());
        }
        if (strategy == BacktestStrategyId.CUSTOM) {
            try {
                strategyValidator.validate(request.strategyCode());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED, exception.getMessage());
            }
        }
    }
```

Add parse methods that throw Backtest-specific `BusinessException`:

```java
    private BacktestStrategyId parseStrategy(String value) {
        try {
            return BacktestStrategyId.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_STRATEGY, ErrorCode.BACKTEST_UNSUPPORTED_STRATEGY.defaultMessage());
        }
    }

    private BacktestPeriod parsePeriod(String value) {
        try {
            return BacktestPeriod.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_UNSUPPORTED_PERIOD, ErrorCode.BACKTEST_UNSUPPORTED_PERIOD.defaultMessage());
        }
    }
```

- [ ] **Step 5: Add read/list/validate service methods**

Add:

```java
    public StrategyValidationDto validateStrategy(ValidateStrategyRequest request) {
        try {
            return strategyValidator.validate(request.strategyCode());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BACKTEST_STRATEGY_COMPILE_FAILED, exception.getMessage());
        }
    }

    public BacktestRunDto getRun(Long userId, String runId) {
        return repository.findRunForUser(userId, runId)
            .map(mapper::toRunDto)
            .orElseThrow(() -> new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, ErrorCode.BACKTEST_RUN_NOT_FOUND.defaultMessage()));
    }

    public BacktestResultDto getResult(Long userId, String runId) {
        BacktestRun run = repository.findRunForUser(userId, runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, ErrorCode.BACKTEST_RUN_NOT_FOUND.defaultMessage()));
        BacktestResult result = repository.findResultForUser(userId, runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BACKTEST_RESULT_NOT_READY, ErrorCode.BACKTEST_RESULT_NOT_READY.defaultMessage()));
        return mapper.toResultDto(run, result);
    }

    public PageResponse<BacktestRunDto> listRuns(Long userId, String symbol, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        PageResponse<BacktestRun> runs = repository.listRuns(userId, symbol, safePage, safeSize);
        return PageResponse.of(
            runs.items().stream().map(mapper::toRunDto).toList(),
            runs.page(),
            runs.size(),
            runs.totalElements()
        );
    }
```

- [ ] **Step 6: Run service tests**

Run:

```powershell
.\mvnw.cmd -pl stock-module-backtest test "-Dtest=BacktestServiceTest"
```

Expected: PASS.

- [ ] **Step 7: Commit Task 5**

```powershell
git add stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/service stock-module-backtest/src/test/java/dowob/xyz/stockwebv2/backtest/service
git commit -m "feat: add backtest service"
```

---

### Task 6: Expose Backtest Controller and API Integration Tests

**Files:**
- Create: `stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api/BacktestController.java`
- Test: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestApiIT.java`

- [ ] **Step 1: Write failing controller integration tests**

Create `BacktestApiIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BacktestApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void backtestEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/backtests/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRunBody()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
    }

    @Test
    void authenticatedUserCanCreateReadAndListOwnRun() throws Exception {
        AuthTokens tokens = register("bt-owner@example.com", "btowner", "Password1");

        String createBody = mockMvc.perform(post("/api/v1/backtests/runs")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRunBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.status", equalTo("succeeded")))
            .andExpect(jsonPath("$.data.id", notNullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String runId = objectMapper.readTree(createBody).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/backtests/runs/" + runId)
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.symbol", equalTo("AAPL")));

        mockMvc.perform(get("/api/v1/backtests/runs/" + runId + "/result")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.tradeCount", equalTo(12)))
            .andExpect(jsonPath("$.data.trades.length()", equalTo(12)));

        mockMvc.perform(get("/api/v1/backtests/runs?page=0&size=20&symbol=AAPL")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)))
            .andExpect(jsonPath("$.data.totalElements", equalTo(1)));
    }

    @Test
    void usersCannotReadOtherUsersRuns() throws Exception {
        AuthTokens owner = register("bt-private-owner@example.com", "btprivateowner", "Password1");
        AuthTokens other = register("bt-private-other@example.com", "btprivateother", "Password1");
        String createBody = mockMvc.perform(post("/api/v1/backtests/runs")
                .header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRunBody()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String runId = objectMapper.readTree(createBody).get("data").get("id").asText();

        mockMvc.perform(get("/api/v1/backtests/runs/" + runId)
                .header("Authorization", "Bearer " + other.accessToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", equalTo("BACKTEST_RUN_NOT_FOUND")));
    }

    private String validRunBody() {
        return """
            {
              "strategyId":"ma_cross",
              "strategyCode":null,
              "symbol":"AAPL",
              "period":"3Y",
              "initialCapital":100000,
              "currency":"USD",
              "benchmark":"buy_hold",
              "dataMode":"cached"
            }
            """;
    }
}
```

Add `register` and `readTokens` helpers copied from `AuthFlowIT`.

- [ ] **Step 2: Run failing API tests**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true" "-Dit.test=BacktestApiIT"
```

Expected: FAIL because controller does not exist.

- [ ] **Step 3: Add `BacktestController`**

Create:

```java
package dowob.xyz.stockwebv2.backtest.api;

import dowob.xyz.stockwebv2.backtest.service.BacktestService;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import jakarta.validation.Valid;
import org.slf4j.MDC;
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
        return ApiResponse.success(backtestService.createRun(authenticatedUserId(authentication), request), meta());
    }

    @PostMapping("/strategies/validate")
    public ApiResponse<StrategyValidationDto> validateStrategy(@Valid @RequestBody ValidateStrategyRequest request) {
        return ApiResponse.success(backtestService.validateStrategy(request), meta());
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<BacktestRunDto> getRun(@PathVariable String runId, Authentication authentication) {
        return ApiResponse.success(backtestService.getRun(authenticatedUserId(authentication), runId), meta());
    }

    @GetMapping("/runs/{runId}/result")
    public ApiResponse<BacktestResultDto> getResult(@PathVariable String runId, Authentication authentication) {
        return ApiResponse.success(backtestService.getResult(authenticatedUserId(authentication), runId), meta());
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<BacktestRunDto>> listRuns(
        @RequestParam(required = false) String symbol,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Authentication authentication
    ) {
        return ApiResponse.success(backtestService.listRuns(authenticatedUserId(authentication), symbol, page, size), meta());
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
    }

    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
```

- [ ] **Step 4: Run API tests**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true" "-Dit.test=BacktestApiIT"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 6**

```powershell
git add stock-module-backtest/src/main/java/dowob/xyz/stockwebv2/backtest/api stock-start/src/test/java/dowob/xyz/stockwebv2/start/BacktestApiIT.java
git commit -m "feat: expose backtest api"
```

---

### Task 7: Add E2E Flow, Cleanup, and Full Verification

**Files:**
- Modify: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/DatabaseCleaner.java`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/BacktestE2E.java`
- Modify: `docs/superpowers/specs/2026-05-16-backtest-api-mvp-design.md` only if implementation reveals a documented contract mismatch.

- [ ] **Step 1: Update E2E database cleanup**

Modify `DatabaseCleaner.cleanUserData()`:

```java
    public void cleanUserData() {
        jdbcTemplate.execute("DELETE FROM backtest_runs");
        jdbcTemplate.execute("DELETE FROM users");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
```

The child Backtest tables use `ON DELETE CASCADE` through `backtest_runs`, so deleting `backtest_runs` cleans KPI, equity, drawdown, monthly returns, and trades.

- [ ] **Step 2: Add Backtest E2E test**

Create `BacktestE2E.java`:

```java
package dowob.xyz.stockwebv2.start.e2e;

import dowob.xyz.stockwebv2.start.e2e.support.AbstractStockE2ETest;
import dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import static dowob.xyz.stockwebv2.start.e2e.support.AuthE2EHelper.bearerToken;
import static dowob.xyz.stockwebv2.start.e2e.support.StockE2EAssertions.apiSuccess;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Backtest E2E")
class BacktestE2E extends AbstractStockE2ETest {

    @Test
    void authenticatedUserCanCreateRunReadResultAndListRuns() throws Exception {
        AuthE2EHelper.AuthSession session = auth.register("backtest-e2e@example.com", "backteste2e", "Password1");

        String createResponse = mockMvc.perform(post("/api/v1/backtests/runs")
                .with(bearerToken(session.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "strategyId":"ma_cross",
                      "strategyCode":null,
                      "symbol":"AAPL",
                      "period":"3Y",
                      "initialCapital":100000,
                      "currency":"USD",
                      "benchmark":"buy_hold",
                      "dataMode":"cached"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.status", equalTo("succeeded")))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(createResponse).get("data");
        String runId = data.get("id").asText();

        mockMvc.perform(get("/api/v1/backtests/runs/" + runId + "/result")
                .with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.kpis.tradeCount", equalTo(12)))
            .andExpect(jsonPath("$.data.equityCurve.length()", equalTo(12)))
            .andExpect(jsonPath("$.data.monthlyReturns.length()", equalTo(36)))
            .andExpect(jsonPath("$.data.drawdownCurve.length()", equalTo(12)))
            .andExpect(jsonPath("$.data.trades.length()", equalTo(12)));

        mockMvc.perform(get("/api/v1/backtests/runs?page=0&size=20")
                .with(bearerToken(session.accessToken())))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.items.length()", equalTo(1)));
    }

    @Test
    void customStrategyValidationReturnsApiResponse() throws Exception {
        AuthE2EHelper.AuthSession session = auth.register("backtest-custom@example.com", "backtestcustom", "Password1");

        mockMvc.perform(post("/api/v1/backtests/strategies/validate")
                .with(bearerToken(session.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategyCode\":\"function strategy({ bars }) { return null; }\"}"))
            .andExpect(status().isOk())
            .andExpect(apiSuccess())
            .andExpect(jsonPath("$.data.valid", equalTo(true)));
    }
}
```

- [ ] **Step 3: Run E2E tests**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am test -Pe2e
```

Expected: PASS, including `Backtest E2E`.

- [ ] **Step 4: Run unit tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Run integration tests**

Run:

```powershell
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true"
```

Expected: BUILD SUCCESS, including Backtest ITs.

- [ ] **Step 6: Run diff hygiene**

Run:

```powershell
git diff --check
```

Expected: no whitespace errors. LF to CRLF warnings are acceptable on this Windows workspace.

- [ ] **Step 7: Commit Task 7**

```powershell
git add stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/support/DatabaseCleaner.java stock-start/src/test/java/dowob/xyz/stockwebv2/start/e2e/BacktestE2E.java
git commit -m "test: add backtest e2e coverage"
```

---

### Task 8: Final PR Readiness

**Files:**
- Review: all changed files
- No new files required unless verification exposes a contract/documentation gap

- [ ] **Step 1: Inspect final status**

Run:

```powershell
git status --short --branch
git log --oneline --decorate -8
```

Expected: branch is clean after commits and ahead of `origin/develop`.

- [ ] **Step 2: Review scoped diff**

Run:

```powershell
git diff --stat origin/develop..HEAD
git diff --name-only origin/develop..HEAD
```

Expected: diff includes only Backtest module, Backtest migration, Backtest tests, poms, ErrorCode, DatabaseCleaner, design/plan docs.

- [ ] **Step 3: Run final verification commands**

Run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true"
.\mvnw.cmd -pl stock-start -am test -Pe2e
git diff --check
```

Expected: all Maven commands end with `BUILD SUCCESS`; `git diff --check` reports no whitespace errors.

- [ ] **Step 4: Push branch**

Run:

```powershell
git push origin feature/backtest-api-mvp
```

Expected: remote branch is created or updated.

- [ ] **Step 5: Open PR**

Title:

```text
Add authenticated Backtest API MVP
```

Body:

```markdown
## Summary
- Add `stock-module-backtest` with authenticated Backtest run/result APIs.
- Persist Backtest runs, KPI, equity, drawdown, monthly returns, and trades in normalized PostgreSQL tables.
- Add deterministic result generation for the MVP while preserving future async/real-engine status values.
- Enforce user ownership for run/result reads and list queries.
- Add unit, integration, and E2E coverage.

## Test Plan
- `./mvnw.cmd test`
- `./mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true"`
- `./mvnw.cmd -pl stock-start -am test -Pe2e`
- `git diff --check`
```

- [ ] **Step 6: Watch CI**

Use GitHub Actions run for the pushed commit. Expected jobs:

- Unit Tests: success
- Integration Tests: success
- E2E Tests: success

If a CI job fails, fetch the job logs, identify the first failing test or build error, fix only that cause, rerun the matching local command, commit, push, and watch CI again.

---

## Self-Review Notes

Spec coverage:

- Authenticated endpoints and user ownership are covered by Tasks 5, 6, and 7.
- Normalized schema is covered by Tasks 1 and 4.
- Deterministic result generation is covered by Task 3.
- PageResponse list runs are covered by Tasks 4, 5, and 6.
- Strategy snapshot and future `strategy_version_id` are covered by Tasks 1, 4, and 5.
- No market data tables are introduced.
- No async worker is introduced.

Type consistency:

- External run ids use `bt_` + UUID string throughout service, mapper, repository, and tests.
- API enum values remain lowercase or compact FE strings; DB stores those API values.
- `BacktestResultDto` uses frontend field names: `equityCurve`, `monthlyReturns`, `drawdownCurve`, and `trades`.

Verification:

- The plan starts with failing tests before implementation tasks.
- Each implementation slice has a focused verification command and a commit step.
