INSERT INTO assets(symbol, name, asset_type, market, currency, sector, tradeable, active)
VALUES
('AAPL', 'Apple Inc.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('NVDA', 'NVIDIA Corp.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('TSLA', 'Tesla, Inc.', 'STOCK', 'US', 'USD', 'Auto', TRUE, TRUE),
('MSFT', 'Microsoft Corp.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('2330.TW', '台積電 TSMC', 'STOCK', 'TW', 'TWD', 'Tech', TRUE, TRUE),
('GOOGL', 'Alphabet Inc.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('AMZN', 'Amazon.com', 'STOCK', 'US', 'USD', 'Retail', TRUE, TRUE),
('META', 'Meta Platforms', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('BTC', 'Bitcoin', 'CRYPTO', 'CRYPTO', 'USD', 'Crypto', TRUE, TRUE),
('ETH', 'Ethereum', 'CRYPTO', 'CRYPTO', 'USD', 'Crypto', TRUE, TRUE),
('SOL', 'Solana', 'CRYPTO', 'CRYPTO', 'USD', 'Crypto', TRUE, TRUE),
('USD/TWD', '美元/新台幣', 'FX', 'FX', 'TWD', NULL, FALSE, TRUE),
('EUR/USD', '歐元/美元', 'FX', 'FX', 'USD', NULL, FALSE, TRUE),
('USD/JPY', '美元/日圓', 'FX', 'FX', 'JPY', NULL, FALSE, TRUE),
('US10Y', 'US 10-Year Treasury', 'BOND', 'US', 'USD', 'Bond', FALSE, TRUE),
('US2Y', 'US 2-Year Treasury', 'BOND', 'US', 'USD', 'Bond', FALSE, TRUE),
('DE10Y', 'German 10-Year Bund', 'BOND', 'DE', 'EUR', 'Bond', FALSE, TRUE),
('JP10Y', 'Japan 10-Year', 'BOND', 'JP', 'JPY', 'Bond', FALSE, TRUE),
('TW10Y', '中華民國 10-Year', 'BOND', 'TW', 'TWD', 'Bond', FALSE, TRUE)
ON CONFLICT (symbol) DO NOTHING;

INSERT INTO asset_latest_prices(asset_id, price, change, change_percent, volume_text, high, low, price_time)
SELECT id, price, change, change_percent, volume_text, high, low, NOW()
FROM (
    VALUES
    ('AAPL', 218.40, 1.42, 0.66, '52.1M', 219.10, 215.80),
    ('NVDA', 1142.83, 28.40, 2.55, '38.4M', 1148.20, 1118.00),
    ('TSLA', 178.22, -3.18, -1.75, '94.3M', 182.60, 177.40),
    ('MSFT', 432.85, 2.10, 0.49, '18.2M', 433.90, 430.10),
    ('2330.TW', 945.00, 12.00, 1.29, '32.0M', 950.00, 932.00),
    ('GOOGL', 174.62, 0.84, 0.48, '21.0M', 175.10, 172.80),
    ('AMZN', 192.34, -1.04, -0.54, '32.4M', 195.20, 191.40),
    ('META', 514.20, 4.32, 0.85, '14.8M', 516.00, 508.40),
    ('BTC', 67842.40, 1842.10, 2.79, '$28.4B', 68120.00, 65400.00),
    ('ETH', 3482.18, 84.20, 2.48, '$14.2B', 3510.00, 3380.00),
    ('SOL', 168.42, -2.18, -1.28, '$3.4B', 172.20, 166.80),
    ('USD/TWD', 32.418, 0.024, 0.07, NULL, 32.450, 32.380),
    ('EUR/USD', 1.0832, -0.0014, -0.13, NULL, 1.0851, 1.0820),
    ('USD/JPY', 156.42, 0.32, 0.20, NULL, 156.80, 155.90),
    ('US10Y', 4.218, 0.014, 0.014, NULL, NULL, NULL),
    ('US2Y', 4.842, -0.012, -0.012, NULL, NULL, NULL),
    ('DE10Y', 2.458, 0.008, 0.008, NULL, NULL, NULL),
    ('JP10Y', 0.984, 0.018, 0.018, NULL, NULL, NULL),
    ('TW10Y', 1.642, 0.004, 0.004, NULL, NULL, NULL)
) AS seed(symbol, price, change, change_percent, volume_text, high, low)
JOIN assets a ON a.symbol = seed.symbol
ON CONFLICT (asset_id) DO UPDATE SET
    price = EXCLUDED.price,
    change = EXCLUDED.change,
    change_percent = EXCLUDED.change_percent,
    volume_text = EXCLUDED.volume_text,
    high = EXCLUDED.high,
    low = EXCLUDED.low,
    price_time = EXCLUDED.price_time,
    updated_at = NOW();

INSERT INTO fx_rates(base_currency, quote_currency, rate, rate_time)
VALUES
('USD', 'TWD', 32.418, NOW()),
('EUR', 'USD', 1.0832, NOW()),
('USD', 'JPY', 156.42, NOW()),
('TWD', 'USD', 0.030847, NOW())
ON CONFLICT (base_currency, quote_currency) DO UPDATE SET
    rate = EXCLUDED.rate,
    rate_time = EXCLUDED.rate_time,
    updated_at = NOW();
