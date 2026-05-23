-- non-transactional

CREATE MATERIALIZED VIEW kline_1m
WITH (timescaledb.continuous) AS
SELECT
    asset_id,
    time_bucket('1 minute', time) AS bucket,
    first(price, time)            AS open,
    max(price)                    AS high,
    min(price)                    AS low,
    last(price, time)             AS close,
    sum(volume)                   AS volume
FROM market_prices
GROUP BY bucket, asset_id
WITH NO DATA;

-- start_offset 必須涵蓋 backfill horizon (BackfillController 上限 90 day),否則歷史回填
-- 寫入後此 view 不會 materialize,/klines 查不到。設 120 day 留 buffer。
SELECT add_continuous_aggregate_policy('kline_1m',
    start_offset      => INTERVAL '120 days',
    end_offset        => INTERVAL '30 seconds',
    schedule_interval => INTERVAL '30 seconds'
);

CREATE MATERIALIZED VIEW kline_5m
WITH (timescaledb.continuous) AS
SELECT
    asset_id,
    time_bucket('5 minutes', time) AS bucket,
    first(price, time)             AS open,
    max(price)                     AS high,
    min(price)                     AS low,
    last(price, time)              AS close,
    sum(volume)                    AS volume
FROM market_prices
GROUP BY bucket, asset_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('kline_5m',
    start_offset      => INTERVAL '120 days',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute'
);

CREATE MATERIALIZED VIEW kline_15m
WITH (timescaledb.continuous) AS
SELECT
    asset_id,
    time_bucket('15 minutes', time) AS bucket,
    first(price, time)              AS open,
    max(price)                      AS high,
    min(price)                      AS low,
    last(price, time)               AS close,
    sum(volume)                     AS volume
FROM market_prices
GROUP BY bucket, asset_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('kline_15m',
    start_offset      => INTERVAL '120 days',
    end_offset        => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes'
);

CREATE MATERIALIZED VIEW kline_1h
WITH (timescaledb.continuous) AS
SELECT
    asset_id,
    time_bucket('1 hour', time) AS bucket,
    first(price, time)          AS open,
    max(price)                  AS high,
    min(price)                  AS low,
    last(price, time)           AS close,
    sum(volume)                 AS volume
FROM market_prices
GROUP BY bucket, asset_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('kline_1h',
    start_offset      => INTERVAL '120 days',
    end_offset        => INTERVAL '15 minutes',
    schedule_interval => INTERVAL '15 minutes'
);

CREATE MATERIALIZED VIEW kline_1d
WITH (timescaledb.continuous) AS
SELECT
    asset_id,
    time_bucket('1 day', time) AS bucket,
    first(price, time)         AS open,
    max(price)                 AS high,
    min(price)                 AS low,
    last(price, time)          AS close,
    sum(volume)                AS volume
FROM market_prices
GROUP BY bucket, asset_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('kline_1d',
    start_offset      => INTERVAL '1 year',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);
