ALTER TABLE funding_request
 ADD COLUMN purpose VARCHAR(500) NOT NULL DEFAULT '',
 ADD COLUMN approved_amount DECIMAL(18,2) NULL,
 ADD COLUMN review_note VARCHAR(500) NOT NULL DEFAULT '',
 ADD CONSTRAINT ck_funding_approved_amount CHECK (approved_amount IS NULL OR (approved_amount > 0 AND approved_amount <= 1000000));
UPDATE funding_request SET approved_amount=amount WHERE status='APPROVED';
CREATE TABLE simulation_funding_credit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 admin_id BIGINT NULL,
 request_id BIGINT NULL,
 source_type VARCHAR(10) NOT NULL,
 amount DECIMAL(18,2) NOT NULL,
 note VARCHAR(500) NOT NULL DEFAULT '',
 operation_id VARCHAR(36) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES app_user(id),
 FOREIGN KEY (admin_id) REFERENCES app_user(id),
 FOREIGN KEY (request_id) REFERENCES funding_request(id),
 UNIQUE KEY uq_funding_credit_request (request_id),
 UNIQUE KEY uq_funding_credit_operation (admin_id,operation_id),
 CHECK (amount > 0 AND amount <= 1000000),
 CHECK ((source_type='REQUEST' AND request_id IS NOT NULL) OR (source_type='DIRECT' AND request_id IS NULL AND admin_id IS NOT NULL AND operation_id IS NOT NULL)),
 INDEX ix_funding_credit_user (user_id,id)
) ENGINE=InnoDB;
-- Preserve previously approved funding exactly; leave historical trades untouched.
INSERT INTO simulation_funding_credit (user_id,admin_id,request_id,source_type,amount,note,created_at)
 SELECT user_id,reviewed_by,id,'REQUEST',amount,review_note,COALESCE(reviewed_at,created_at)
 FROM funding_request WHERE status='APPROVED';
CREATE TABLE simulation_trade_quote (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 asset_symbol VARCHAR(10) NOT NULL,
 transaction_type VARCHAR(4) NOT NULL,
 quantity DECIMAL(28,12) NOT NULL,
 unit_price DECIMAL(28,12) NOT NULL,
 cash_amount DECIMAL(28,2) NOT NULL,
 cash_before DECIMAL(38,2) NOT NULL,
 cash_after DECIMAL(38,2) NOT NULL,
 source_updated_at DATETIME(6) NOT NULL,
 fetched_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 FOREIGN KEY (user_id) REFERENCES app_user(id),
 FOREIGN KEY (asset_symbol) REFERENCES supported_asset(symbol),
 CHECK (transaction_type IN ('BUY','SELL')),
 CHECK (quantity > 0 AND unit_price > 0 AND cash_amount > 0),
 CHECK (cash_before >= 0 AND cash_after >= 0),
 INDEX ix_trade_quote_user (user_id,expires_at)
) ENGINE=InnoDB;
ALTER TABLE simulated_trade
 ADD COLUMN quote_id VARCHAR(36) NULL,
 ADD CONSTRAINT uq_simulated_trade_quote UNIQUE (quote_id),
 ADD CONSTRAINT fk_simulated_trade_quote FOREIGN KEY (quote_id) REFERENCES simulation_trade_quote(id);
