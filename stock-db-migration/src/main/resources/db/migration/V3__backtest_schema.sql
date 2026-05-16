CREATE TABLE backtest_runs (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    strategy_version_id BIGINT,
    strategy_id VARCHAR(32) NOT NULL,
    strategy_label VARCHAR(128) NOT NULL,
    strategy_code TEXT,
    symbol VARCHAR(32) NOT NULL,
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
