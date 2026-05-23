CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE market_prices (
    asset_id BIGINT         NOT NULL,
    time     TIMESTAMPTZ    NOT NULL,
    price    NUMERIC(24, 8) NOT NULL,
    volume   NUMERIC(24, 8),
    PRIMARY KEY (asset_id, time)
);

SELECT create_hypertable('market_prices', by_range('time'));

CREATE INDEX idx_market_prices_asset_time ON market_prices (asset_id, time DESC);
