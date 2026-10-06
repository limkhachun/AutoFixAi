CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    preferred_language VARCHAR(5) NOT NULL DEFAULT 'en',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT ck_user_language CHECK (preferred_language IN ('en', 'zh'))
) ENGINE=InnoDB;

CREATE TABLE supported_asset (
    symbol VARCHAR(10) NOT NULL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    market_provider_id VARCHAR(80) NOT NULL,
    CONSTRAINT uq_provider_id UNIQUE (market_provider_id)
) ENGINE=InnoDB;

INSERT INTO supported_asset (symbol, name, market_provider_id) VALUES
('BTC', 'Bitcoin', 'bitcoin'), ('ETH', 'Ethereum', 'ethereum'), ('SOL', 'Solana', 'solana');

CREATE TABLE portfolio_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset_symbol VARCHAR(10) NOT NULL,
    transaction_type VARCHAR(4) NOT NULL,
    quantity DECIMAL(28, 12) NOT NULL,
    unit_price_usd DECIMAL(28, 12) NOT NULL,
    fee_usd DECIMAL(28, 12) NOT NULL DEFAULT 0,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC transaction time',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_transaction_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_transaction_asset FOREIGN KEY (asset_symbol) REFERENCES supported_asset(symbol),
    CONSTRAINT ck_transaction_type CHECK (transaction_type IN ('BUY', 'SELL')),
    CONSTRAINT ck_transaction_quantity CHECK (quantity > 0),
    CONSTRAINT ck_transaction_price CHECK (unit_price_usd > 0),
    CONSTRAINT ck_transaction_fee CHECK (fee_usd >= 0),
    INDEX ix_user_asset_history (user_id, asset_symbol, occurred_at, id),
    INDEX ix_user_history (user_id, occurred_at, id)
) ENGINE=InnoDB;

CREATE TABLE market_price (
    asset_symbol VARCHAR(10) NOT NULL PRIMARY KEY,
    price_usd DECIMAL(28, 12) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    fetched_at DATETIME(6) NOT NULL COMMENT 'UTC fetch time; used to detect stale prices',
    CONSTRAINT fk_price_asset FOREIGN KEY (asset_symbol) REFERENCES supported_asset(symbol),
    CONSTRAINT ck_market_price CHECK (price_usd > 0)
) ENGINE=InnoDB;
