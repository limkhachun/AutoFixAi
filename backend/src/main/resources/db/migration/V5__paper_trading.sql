CREATE TABLE funding_request (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 amount DECIMAL(18,2) NOT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 reviewed_at TIMESTAMP(6) NULL,
 reviewed_by BIGINT NULL,
 FOREIGN KEY (user_id) REFERENCES app_user(id),
 FOREIGN KEY (reviewed_by) REFERENCES app_user(id),
 CHECK (amount > 0 AND amount <= 1000000),
 CHECK (status IN ('PENDING','APPROVED','REJECTED')),
 INDEX ix_funding_user (user_id,id)
) ENGINE=InnoDB;
CREATE TABLE simulated_trade (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 asset_symbol VARCHAR(10) NOT NULL,
 transaction_type VARCHAR(4) NOT NULL,
 quantity DECIMAL(28,12) NOT NULL,
 unit_price DECIMAL(28,12) NOT NULL,
 cash_amount DECIMAL(28,2) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES app_user(id),
 FOREIGN KEY (asset_symbol) REFERENCES supported_asset(symbol),
 CHECK (transaction_type IN ('BUY','SELL')),
 CHECK (quantity > 0 AND unit_price > 0 AND cash_amount > 0),
 INDEX ix_simulated_user (user_id,id)
) ENGINE=InnoDB;
