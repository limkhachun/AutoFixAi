package com.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="simulation.admin-email=simulation-admin@example.invalid")
class SimulationIntegrationTests {
    @Autowired SimulationService service;
    @Autowired JdbcTemplate jdbc;
    final String admin="simulation-admin@example.invalid";
    private long add(String email) {
        jdbc.update("INSERT INTO app_user(email,password_hash) VALUES (?,?)",email,"not-a-login-password");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email=?",Long.class,email);
    }
    private void clean(long id) {
        jdbc.update("DELETE FROM simulated_trade WHERE user_id=?",id);
        jdbc.update("DELETE FROM funding_request WHERE user_id=?",id);
        jdbc.update("DELETE FROM app_user WHERE id=?",id);
    }
    private void rejected(String code,Runnable action) { assertEquals(code,assertThrows(PortfolioService.Rejected.class,action::run).code); }
    private MarketService.Quote quote(Instant time) { return new MarketService.Quote("BTC","100.00",time,time,"LIVE"); }
    private SimulationService.Order order(PortfolioService.Type type,String quantity) { return new SimulationService.Order("BTC",type,new BigDecimal(quantity)); }
    @Test void fundingIsolationApprovalsTradingAndConcurrentSpending() throws Exception {
        String email="simulation-"+UUID.randomUUID()+"@example.invalid";
        String other="simulation-other-"+UUID.randomUUID()+"@example.invalid";
        long adminId=add(admin),id=add(email),otherId=add(other);
        var pool=Executors.newFixedThreadPool(2);
        try {
            assertEquals(0,new BigDecimal(service.wallet(email).cash()).signum());
            rejected("ADMIN_REQUIRED",()->service.adminRequests(email));
            rejected("INSUFFICIENT_CASH",()->service.trade(email,order(PortfolioService.Type.BUY,"1"),quote(Instant.now())));
            service.request(email,new SimulationService.FundingInput(new BigDecimal("100.00")));
            rejected("REQUEST_PENDING",()->service.request(email,new SimulationService.FundingInput(BigDecimal.ONE)));
            long request=service.wallet(email).requests().getFirst().id();
            assertTrue(service.wallet(other).requests().isEmpty());
            rejected("ADMIN_REQUIRED",()->service.review(email,request,true));
            service.review(admin,request,true);
            rejected("ALREADY_REVIEWED",()->service.review(admin,request,true));
            assertEquals(new BigDecimal("100.00"),new BigDecimal(service.wallet(email).cash()));
            rejected("PRICE_UNAVAILABLE",()->service.trade(email,order(PortfolioService.Type.BUY,"1"),quote(Instant.now().minusSeconds(601))));
            Callable<Boolean> buy=()->{try{service.trade(email,order(PortfolioService.Type.BUY,"1"),quote(Instant.now()));return true;}catch(PortfolioService.Rejected e){assertEquals("INSUFFICIENT_CASH",e.code);return false;}};
            var a=pool.submit(buy);var b=pool.submit(buy);
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(0,new BigDecimal(service.wallet(email).cash()).signum());
            rejected("OVERSELL",()->service.trade(email,order(PortfolioService.Type.SELL,"2"),quote(Instant.now())));
            service.trade(email,order(PortfolioService.Type.SELL,"1"),quote(Instant.now()));
            assertEquals(new BigDecimal("100.00"),new BigDecimal(service.wallet(email).cash()));
            assertTrue(service.wallet(other).trades().isEmpty());
            service.request(email,new SimulationService.FundingInput(BigDecimal.ONE));
            long rejectedRequest=service.wallet(email).requests().getFirst().id();service.review(admin,rejectedRequest,false);
            assertEquals(new BigDecimal("100.00"),new BigDecimal(service.wallet(email).cash()));
            assertEquals("REJECTED",service.wallet(email).requests().getFirst().status());
            assertEquals(2,service.wallet(email).trades().size());
        } finally { pool.shutdownNow();clean(id);clean(otherId);clean(adminId); }
    }
}
