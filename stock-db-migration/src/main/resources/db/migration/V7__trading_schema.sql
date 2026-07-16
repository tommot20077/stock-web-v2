CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
    user_id BIGINT NOT NULL REFERENCES users(id),
    asset_id BIGINT NOT NULL REFERENCES assets(id),
    type VARCHAR(10) NOT NULL,
    quantity NUMERIC(24, 8) NOT NULL,
    price NUMERIC(24, 8) NOT NULL,
    fee NUMERIC(24, 8) NOT NULL DEFAULT 0,
    note VARCHAR(500),
    executed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_transactions_uuid UNIQUE (uuid),
    CONSTRAINT ck_transactions_type CHECK (type IN ('BUY', 'SELL')),
    CONSTRAINT ck_transactions_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_transactions_price_positive CHECK (price > 0),
    CONSTRAINT ck_transactions_fee_non_negative CHECK (fee >= 0)
);

CREATE INDEX idx_transactions_user_created ON transactions (user_id, created_at DESC, id DESC);
CREATE INDEX idx_transactions_user_asset_created ON transactions (user_id, asset_id, created_at DESC, id DESC);

CREATE TABLE holdings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    asset_id BIGINT NOT NULL REFERENCES assets(id),
    total_quantity NUMERIC(24, 8) NOT NULL,
    avg_cost NUMERIC(24, 8) NOT NULL,
    realized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_holdings_user_asset UNIQUE (user_id, asset_id),
    CONSTRAINT ck_holdings_quantity_non_negative CHECK (total_quantity >= 0),
    CONSTRAINT ck_holdings_avg_cost_non_negative CHECK (avg_cost >= 0)
);

CREATE INDEX idx_holdings_user ON holdings (user_id);
