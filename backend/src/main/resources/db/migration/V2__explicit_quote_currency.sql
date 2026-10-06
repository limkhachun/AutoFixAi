-- Preserve the currency of any historical USD data rather than relabeling it.
ALTER TABLE portfolio_transaction DROP CHECK ck_transaction_price, DROP CHECK ck_transaction_fee;
ALTER TABLE portfolio_transaction
    ADD CONSTRAINT ck_transaction_price CHECK (unit_price > 0),
    ADD CONSTRAINT ck_transaction_fee CHECK (fee >= 0),
    RENAME COLUMN unit_price_usd TO unit_price,
    RENAME COLUMN fee_usd TO fee;
ALTER TABLE portfolio_transaction
    ADD COLUMN quote_currency CHAR(3) NOT NULL DEFAULT 'USD',
    ADD CONSTRAINT ck_transaction_currency CHECK (quote_currency IN ('USD','MYR'));
ALTER TABLE portfolio_transaction ALTER COLUMN quote_currency SET DEFAULT 'MYR';

ALTER TABLE market_price DROP CHECK ck_market_price;
ALTER TABLE market_price RENAME COLUMN price_usd TO price;
ALTER TABLE market_price
    ADD CONSTRAINT ck_market_price CHECK (price > 0),
    ADD COLUMN quote_currency CHAR(3) NOT NULL DEFAULT 'USD',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (asset_symbol, quote_currency),
    ADD CONSTRAINT ck_price_currency CHECK (quote_currency IN ('USD','MYR'));
ALTER TABLE market_price ALTER COLUMN quote_currency SET DEFAULT 'MYR';
