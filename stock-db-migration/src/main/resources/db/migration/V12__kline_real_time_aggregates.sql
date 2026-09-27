-- K 線改為 real-time aggregate:查詢時把尚未 materialize 的最近資料當場從 market_prices 補算。
--
-- TimescaleDB 2.13 起新建的 continuous aggregate 預設 materialized_only = true,
-- 查詢只讀已刷新的部分;依 V5 的刷新策略,最近 1 分鐘(1m)到約 2 小時(1d)的 tick 看不到,
-- K 線圖最後一根與報價卡的當日高低點因此落後。已刷新的區段照舊讀預算結果,額外成本只有最近那一小段。
ALTER MATERIALIZED VIEW kline_1m SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW kline_5m SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW kline_15m SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW kline_1h SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW kline_1d SET (timescaledb.materialized_only = false);
