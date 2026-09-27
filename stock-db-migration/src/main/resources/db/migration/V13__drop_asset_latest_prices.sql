-- 種子價表 asset_latest_prices 只有 V2 寫過一次、從未有程式更新(2026-09-02 架構審查 H-1)。
-- 持倉估值與報價卡已改由 market-data(Redis latest / market_prices / kline_1h)提供,此表不再有讀者。
DROP TABLE IF EXISTS asset_latest_prices;
