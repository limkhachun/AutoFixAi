-- A successful fetch does not necessarily mean the upstream quote is recent.
ALTER TABLE market_price ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT 'UTC provider quote time';
