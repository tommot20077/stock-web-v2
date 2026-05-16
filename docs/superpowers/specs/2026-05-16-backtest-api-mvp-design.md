# stock-web-v2 Backtest API MVP 設計

日期：2026-05-16

## 背景

前端 `D:\end\workspace\vue\stock-v2` 已有 Backtest 頁面與 `backtestApi` adapter 雛型。頁面目前仍使用 mock/deterministic 前端資料產生 KPI、equity curve、monthly returns、drawdown curve 與 trade log。

本階段目標是讓 Backtest 頁面可以切到 real API：後端提供真 HTTP API、真登入保護、真 DB persistence、真查詢與測試，但暫不實作真行情資料、真策略引擎或策略管理頁。這是一個 contract-first 的 Backtest MVP，先固定資料模型與 API 邊界，後續再替換 deterministic engine。

## 已確認決策

- Backtest MVP 採 **真 API + DB persistence + deterministic result generator**。
- 所有 Backtest endpoint 都需要登入。
- Backtest run 綁定 `user_id`，使用者只能查自己的 run/result。
- `POST /api/v1/backtests/runs` 在 MVP 內同步完成，直接產生 `succeeded` result。
- API 狀態 enum 保留 `queued`、`running`、`succeeded`、`failed`、`rejected`，未來可改 async worker 而不破 contract。
- Result 不用單一 JSON 欄位，依功能正規化設表。
- 本階段不建立 market data / market bars 表。
- 策略先存 run snapshot：`strategy_id`、`strategy_code`、策略參數與必要輸入；預留未來策略管理的 nullable `strategy_version_id`。
- `listRuns` 使用後端既有 `PageResponse<T>`，不做 cursor pagination。
- Backtest 獨立為 `stock-module-backtest` Maven module。

## Scope

本階段實作：

- 新增 `stock-module-backtest`。
- 新增 Backtest Flyway migration。
- 新增 Backtest API：
  - `POST /api/v1/backtests/runs`
  - `POST /api/v1/backtests/strategies/validate`
  - `GET /api/v1/backtests/runs/{runId}`
  - `GET /api/v1/backtests/runs/{runId}/result`
  - `GET /api/v1/backtests/runs?page=0&size=20&symbol=AAPL`
- 新增 deterministic result generator。
- 新增 run/result 正規化 persistence。
- 新增 unit、integration、E2E 測試。

本階段不實作：

- 真行情資料模型。
- 真市場資料 provider。
- 真策略引擎或 sandbox。
- 背景 queue / worker / scheduler。
- 策略管理頁與 strategy CRUD。
- 多使用者 sharing / public runs。
- 跨 run 分析、策略比較、portfolio integration。

## Module Boundary

新增模組：

```text
stock-module-backtest
├─ api
├─ domain
├─ engine
├─ repository
└─ service
```

### api

職責：

- `BacktestController`
- request DTO：
  - `CreateBacktestRunRequest`
  - `ValidateStrategyRequest`
- response DTO：
  - `BacktestRunDto`
  - `BacktestResultDto`
  - `BacktestKpisDto`
  - `EquityPointDto`
  - `DrawdownPointDto`
  - `MonthlyReturnDto`
  - `BacktestTradeDto`
  - `StrategyValidationDto`

規則：

- Controller 不直接操作 JDBC。
- Response 一律包在 `ApiResponse<T>`。
- 分頁回 `ApiResponse<PageResponse<BacktestRunDto>>`。
- 使用目前 security principal 取得 user id，不接受 client 傳入 user id。

### domain

職責：

- Backtest run aggregate 與狀態 enum。
- Backtest strategy id/period/result model。
- Backtest result 子資料結構。

建議 enum：

- `BacktestRunStatus`: `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`REJECTED`
- `BacktestStrategyId`: `MA_CROSS`、`RSI`、`MOMENTUM`、`DCA`、`CUSTOM`
- `BacktestPeriod`: `ONE_YEAR`、`THREE_YEARS`、`FIVE_YEARS`

API DTO 對外仍使用前端 contract 的 string value：

- `ma_cross`
- `rsi`
- `momentum`
- `dca`
- `custom`
- `1Y`
- `3Y`
- `5Y`

### engine

職責：

- `BacktestEngine` interface。
- `DeterministicBacktestEngine` implementation。
- Strategy validation 的 MVP 規則。

規則：

- 同一組輸入應產生穩定結果。
- 不依賴外部行情資料或時間。
- 不執行使用者 JS code。
- `custom` strategy validation 僅做保守語法/形狀檢查，MVP 不提供 server-side JS sandbox。

### repository

職責：

- Backtest run/result 寫入。
- Result readback。
- 使用者 ownership 查詢。
- Page-based list。

規則：

- 所有查詢都帶 `user_id`。
- 不提供跨使用者查詢。
- 建立 run/result 應在同一 transaction 內完成。

### service

職責：

- 驗證 request。
- 建立 run。
- 呼叫 deterministic engine。
- 持久化 run/result。
- 查詢 run/result。
- 把 domain model 轉成 API DTO。

## API Contract

Base path:

```text
/api/v1
```

所有 endpoint 都需要 Bearer access token。

### Create Run

```http
POST /api/v1/backtests/runs
```

Request:

```json
{
  "strategyId": "ma_cross",
  "strategyCode": null,
  "symbol": "AAPL",
  "period": "3Y",
  "initialCapital": 100000,
  "currency": "USD",
  "benchmark": "buy_hold",
  "dataMode": "cached"
}
```

Rules:

- `strategyId` must be one of `ma_cross`, `rsi`, `momentum`, `dca`, `custom`。
- `strategyCode` is required when `strategyId = custom`。
- `symbol` must exist in `assets.symbol` and be active。
- `period` must be one of `1Y`, `3Y`, `5Y`。
- `initialCapital` must be finite and greater than 0。
- `currency` is `USD` for MVP。
- `benchmark` is `buy_hold` for MVP。
- `dataMode` accepts `cached` for MVP; `live` may be rejected until real market data exists。

MVP response immediately returns a succeeded run:

```json
{
  "success": true,
  "data": {
    "id": "bt_01HZX",
    "strategyId": "ma_cross",
    "label": "MA Cross (20/50)",
    "symbol": "AAPL",
    "period": "3Y",
    "initialCapital": 100000,
    "currency": "USD",
    "status": "succeeded",
    "progress": 1,
    "createdAt": "2026-05-16T01:30:00Z",
    "startedAt": "2026-05-16T01:30:00Z",
    "completedAt": "2026-05-16T01:30:00Z",
    "error": null
  },
  "error": null,
  "meta": {
    "traceId": "string",
    "timestamp": "2026-05-16T01:30:00Z"
  }
}
```

### Validate Strategy

```http
POST /api/v1/backtests/strategies/validate
```

Request:

```json
{
  "strategyCode": "function strategy({ bars, indicators, broker, i }) { return; }"
}
```

Success response:

```json
{
  "success": true,
  "data": {
    "valid": true,
    "normalizedName": "strategy",
    "warnings": []
  },
  "error": null,
  "meta": {
    "traceId": "string",
    "timestamp": "2026-05-16T01:30:00Z"
  }
}
```

Validation failure uses HTTP 400 and `BACKTEST_STRATEGY_COMPILE_FAILED`。The MVP `fields` list is empty for this `BusinessException` path; field-level entries are only emitted by Bean Validation errors。

### Get Run

```http
GET /api/v1/backtests/runs/{runId}
```

Rules:

- Return 404 if run does not exist for current user。
- Do not reveal whether another user's run id exists。

### Get Result

```http
GET /api/v1/backtests/runs/{runId}/result
```

Response data:

```json
{
  "runId": "bt_01HZX",
  "status": "succeeded",
  "kpis": {
    "totalReturnPct": 42.5,
    "buyHoldReturnPct": 31.2,
    "sharpe": 1.42,
    "cagrPct": 14.1,
    "maxDrawdownPct": -12.8,
    "drawdownDays": 54,
    "winRatePct": 58,
    "tradeCount": 64,
    "profitFactor": 1.8,
    "avgTradePct": 0.74
  },
  "equityCurve": [
    { "t": "2024-01-01", "strategy": 100000, "benchmark": 100000 }
  ],
  "monthlyReturns": [
    { "year": 2026, "month": 1, "returnPct": 2.4 }
  ],
  "drawdownCurve": [
    { "t": "2024-01-01", "drawdownPct": 0 }
  ],
  "trades": [
    {
      "date": "2026-01-12",
      "side": "BUY",
      "entry": 182.1,
      "exit": 195.4,
      "bars": 12,
      "pnl": 420,
      "pnlPct": 2.2
    }
  ]
}
```

### List Runs

```http
GET /api/v1/backtests/runs?page=0&size=20&symbol=AAPL
```

Response uses:

```java
ApiResponse<PageResponse<BacktestRunDto>>
```

Sorting:

- Default sort is `created_at DESC, id DESC`。
- Symbol filter is optional。

Limits:

- Default `page = 0`。
- Default `size = 20`。
- Max `size = 100`。

## Database Design

Migration file:

```text
stock-db-migration/src/main/resources/db/migration/V3__backtest_schema.sql
```

### backtest_runs

Purpose: run 主檔與 strategy snapshot。

Columns:

- `id BIGSERIAL PRIMARY KEY`
- `uuid UUID NOT NULL`
- `user_id BIGINT NOT NULL`
- `strategy_version_id BIGINT NULL`
- `strategy_id VARCHAR(32) NOT NULL`
- `strategy_label VARCHAR(128) NOT NULL`
- `strategy_code TEXT NULL`
- `symbol VARCHAR(50) NOT NULL`
- `period VARCHAR(8) NOT NULL`
- `initial_capital NUMERIC(24, 6) NOT NULL`
- `currency VARCHAR(8) NOT NULL`
- `benchmark VARCHAR(32) NOT NULL`
- `data_mode VARCHAR(32) NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `progress NUMERIC(6, 5) NULL`
- `error_code VARCHAR(64) NULL`
- `error_message TEXT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- `started_at TIMESTAMPTZ NULL`
- `completed_at TIMESTAMPTZ NULL`

Constraints/indexes:

- unique `uuid`
- FK `user_id -> users(id)`
- index `(user_id, created_at DESC, id DESC)`
- index `(user_id, symbol, created_at DESC, id DESC)`

`strategy_version_id` is nullable and has no FK in this MVP because strategy management tables do not exist yet. It is included to preserve a future migration path.

### backtest_kpis

Purpose: one-to-one KPI summary for a run。

Columns:

- `run_id BIGINT PRIMARY KEY`
- `total_return_pct NUMERIC(12, 6) NOT NULL`
- `buy_hold_return_pct NUMERIC(12, 6) NOT NULL`
- `sharpe NUMERIC(12, 6) NOT NULL`
- `cagr_pct NUMERIC(12, 6) NOT NULL`
- `max_drawdown_pct NUMERIC(12, 6) NOT NULL`
- `drawdown_days INT NOT NULL`
- `win_rate_pct NUMERIC(12, 6) NOT NULL`
- `trade_count INT NOT NULL`
- `profit_factor NUMERIC(12, 6) NOT NULL`
- `avg_trade_pct NUMERIC(12, 6) NOT NULL`

Constraints:

- FK `run_id -> backtest_runs(id) ON DELETE CASCADE`

### backtest_equity_points

Purpose: equity curve。

Columns:

- `id BIGSERIAL PRIMARY KEY`
- `run_id BIGINT NOT NULL`
- `point_index INT NOT NULL`
- `point_date DATE NOT NULL`
- `strategy_value NUMERIC(24, 6) NOT NULL`
- `benchmark_value NUMERIC(24, 6) NOT NULL`

Constraints/indexes:

- FK `run_id -> backtest_runs(id) ON DELETE CASCADE`
- unique `(run_id, point_index)`
- index `(run_id, point_date)`

### backtest_drawdown_points

Purpose: drawdown curve。

Columns:

- `id BIGSERIAL PRIMARY KEY`
- `run_id BIGINT NOT NULL`
- `point_index INT NOT NULL`
- `point_date DATE NOT NULL`
- `drawdown_pct NUMERIC(12, 6) NOT NULL`

Constraints/indexes:

- FK `run_id -> backtest_runs(id) ON DELETE CASCADE`
- unique `(run_id, point_index)`
- index `(run_id, point_date)`

### backtest_monthly_returns

Purpose: monthly heatmap。

Columns:

- `id BIGSERIAL PRIMARY KEY`
- `run_id BIGINT NOT NULL`
- `return_year INT NOT NULL`
- `return_month INT NOT NULL`
- `return_pct NUMERIC(12, 6) NOT NULL`

Constraints/indexes:

- FK `run_id -> backtest_runs(id) ON DELETE CASCADE`
- unique `(run_id, return_year, return_month)`

### backtest_trades

Purpose: trade log。

Columns:

- `id BIGSERIAL PRIMARY KEY`
- `run_id BIGINT NOT NULL`
- `trade_index INT NOT NULL`
- `trade_date DATE NOT NULL`
- `side VARCHAR(8) NOT NULL`
- `entry_price NUMERIC(24, 6) NOT NULL`
- `exit_price NUMERIC(24, 6) NOT NULL`
- `bars INT NOT NULL`
- `pnl NUMERIC(24, 6) NOT NULL`
- `pnl_pct NUMERIC(12, 6) NOT NULL`

Constraints/indexes:

- FK `run_id -> backtest_runs(id) ON DELETE CASCADE`
- unique `(run_id, trade_index)`
- index `(run_id, trade_date)`

## Deterministic Result Rules

The deterministic engine generates full FE display data:

- KPI summary。
- Equity curve。
- Monthly returns。
- Drawdown curve。
- Trade log。

MVP size:

- Equity points: fixed 12 points for all periods, sampled across the full requested period。
- Drawdown points: fixed 12 points for all periods, sampled across the full requested period。
- Monthly returns:
  - `1Y`: 12 months。
  - `3Y`: 36 months。
  - `5Y`: 60 months。
- Trades: fixed 12 trades。

The generator seed is derived from stable request inputs:

- `user_id`
- `strategy_id`
- `symbol`
- `period`
- `initial_capital`
- `strategy_code` length/hash when present。

It must not depend on wall clock time except for run timestamps assigned by the service.

## Validation and Errors

Add Backtest-specific error codes to `ErrorCode`:

- `BACKTEST_INVALID_INITIAL_CAPITAL`
- `BACKTEST_UNSUPPORTED_SYMBOL`
- `BACKTEST_UNSUPPORTED_PERIOD`
- `BACKTEST_UNSUPPORTED_STRATEGY`
- `BACKTEST_UNSUPPORTED_DATA_MODE`
- `BACKTEST_STRATEGY_COMPILE_FAILED`
- `BACKTEST_RUN_NOT_FOUND`
- `BACKTEST_RESULT_NOT_READY`

HTTP mapping:

- Invalid request: 400。
- Unsupported symbol/period/strategy/data mode: 400。
- Run/result not found for current user: 404。
- Missing/invalid auth remains existing auth error handling。

Validation rules:

- `initialCapital > 0`。
- `symbol` is non-blank and exists in active assets。
- `period` in supported list。
- `currency = USD` for MVP。
- `benchmark = buy_hold` for MVP。
- `dataMode = cached` for MVP。
- `custom` strategy requires non-blank `strategyCode`。

## Strategy Management Compatibility

The user expects strategy management to be needed later. This MVP intentionally does not build the strategy management page or tables, but it preserves historical correctness and a future migration path:

- Every run stores the exact strategy snapshot used at execution time。
- `strategy_code` is stored on the run for custom strategies。
- `strategy_version_id` is nullable and reserved for future `strategies` / `strategy_versions` tables。
- Future strategy edits must not mutate historical run behavior。
- Future strategy management can add saved strategies, version history, reusable parameters, run history per strategy/version, comparison, draft/active/archived status, and risk/trading policy metadata。

## Testing Plan

### Unit Tests

- `DeterministicBacktestEngineTest`
  - same input produces same result。
  - result includes KPI/equity/monthly/drawdown/trades。
  - period controls monthly return count。
- `BacktestServiceTest`
  - invalid capital rejected。
  - unsupported period rejected。
  - custom strategy requires code。
  - service stores a succeeded run and result。

### Integration Tests

- `BacktestPersistenceIT`
  - Flyway creates all Backtest tables。
  - create run persists normalized result rows。
  - read result reconstructs DTO in API order。
  - list runs returns `PageResponse` sorted newest first。
- `BacktestApiIT`
  - auth required。
  - create run returns `succeeded`。
  - get run/result works for owner。
  - another user receives 404 for run/result。
  - invalid request returns `ApiResponse` error envelope。

### E2E Tests

- `BacktestE2E`
  - register/login。
  - create run。
  - get result。
  - list runs。
  - validate custom strategy success/failure。

### Verification Commands

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl stock-start -am verify "-Dspring-boot.repackage.skip=true"
.\mvnw.cmd -pl stock-start -am test -Pe2e
git diff --check
```

## Frontend Follow-up

The current frontend contract uses `{ data, requestId }` and cursor pagination. Backend foundation uses:

```java
ApiResponse<T>
ApiResponse<PageResponse<T>>
```

After backend implementation, frontend adapter should be adjusted to:

- parse `success/data/error/meta`。
- use `meta.traceId` instead of `requestId`。
- change `listRuns` from cursor params to `page/size/symbol`。
- keep mock mode until API mode parity is verified。

## Acceptance Criteria

The MVP is complete when:

- `stock-module-backtest` builds as part of the Maven reactor。
- Backtest migration runs in Testcontainers PostgreSQL。
- Authenticated user can create, list, and read only their own runs。
- `POST /backtests/runs` persists run, KPI, equity, drawdown, monthly returns, and trades。
- Result response is reconstructed from normalized tables。
- `listRuns` returns `ApiResponse<PageResponse<BacktestRunDto>>`。
- All Backtest API errors use the existing `ApiResponse` error envelope。
- Unit, integration, E2E, and CI checks pass。
