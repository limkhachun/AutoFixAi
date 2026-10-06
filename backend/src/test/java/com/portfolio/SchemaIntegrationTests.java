package com.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SchemaIntegrationTests {
    @Autowired JdbcTemplate jdbc;

    @Test void migrationSeedsExactlyTheSupportedAssets() {
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM supported_asset", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1", Integer.class));
    }

    @Test void databaseRejectsNegativeQuantitiesAndMissingOwners() {
        jdbc.update("INSERT INTO app_user (email,password_hash) VALUES ('schema-check@example.invalid','test-fixture-not-a-login')");
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email='schema-check@example.invalid'", Long.class);
        String insert = "INSERT INTO portfolio_transaction (user_id,asset_symbol,transaction_type,quantity,unit_price,fee,occurred_at) VALUES (?,'BTC','BUY',?,100,0,'2026-10-06 00:00:00')";
        var rejectedQuantity = assertThrows(UncategorizedSQLException.class, () -> jdbc.update(insert, userId, -1));
        assertEquals(3819, rejectedQuantity.getSQLException().getErrorCode());
        assertTrue(rejectedQuantity.getSQLException().getMessage().contains("ck_transaction_quantity"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, -1, 1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_transaction WHERE user_id=?", Integer.class, userId));
        jdbc.update(insert, userId, 1);
        assertEquals("MYR", jdbc.queryForObject("SELECT quote_currency FROM portfolio_transaction WHERE user_id=?", String.class, userId));
    }
}
