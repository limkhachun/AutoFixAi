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
        jdbc.update("DELETE FROM simulation_trade_quote WHERE user_id=?",id);
        jdbc.update("DELETE FROM simulation_funding_credit WHERE user_id=?",id);
        jdbc.update("DELETE FROM funding_request WHERE user_id=?",id);
        jdbc.update("DELETE FROM app_user WHERE id=?",id);
    }
    private void rejected(String code,Runnable action) { assertEquals(code,assertThrows(PortfolioService.Rejected.class,action::run).code); }
    private MarketService.Quote quote(Instant time) { return new MarketService.Quote("BTC","100.00",time,time,"LIVE"); }
    private SimulationService.Order order(PortfolioService.Type type,String quantity) { return new SimulationService.Order("BTC",type,new BigDecimal(quantity)); }
    private MarketService.Market market(MarketService.Quote quote) {
        return new MarketService.Market("MYR","CoinGecko",quote.status(),null,Instant.now(),java.util.List.of(quote));
    }
    private SimulationService.Wallet wallet(String email) { return service.wallet(email,market(quote(Instant.now()))); }
    private void trade(String email,SimulationService.Order order,MarketService.Quote quote) {
        var current=market(quote);
        var preview=service.preview(email,order,current);
        service.trade(email,new SimulationService.Execution(UUID.fromString(preview.id())),current);
    }
    @Test void fundingIsolationApprovalsTradingAndConcurrentSpending() throws Exception {
        String email="simulation-"+UUID.randomUUID()+"@example.invalid";
        String other="simulation-other-"+UUID.randomUUID()+"@example.invalid";
        long adminId=add(admin),id=add(email),otherId=add(other);
        var pool=Executors.newFixedThreadPool(2);
        try {
            assertEquals(0,new BigDecimal(wallet(email).cash()).signum());
            rejected("ADMIN_REQUIRED",()->service.adminRequests(email));
            rejected("INSUFFICIENT_CASH",()->trade(email,order(PortfolioService.Type.BUY,"1"),quote(Instant.now())));
            service.request(email,new SimulationService.FundingInput(new BigDecimal("100.00"),"Practice trading"));
            rejected("REQUEST_PENDING",()->service.request(email,new SimulationService.FundingInput(BigDecimal.ONE,"Practice trading")));
            long request=wallet(email).requests().getFirst().id();
            assertTrue(wallet(other).requests().isEmpty());
            rejected("ADMIN_REQUIRED",()->service.review(email,request,new SimulationService.ReviewInput(true,new BigDecimal("100.00"),"")));
            service.review(admin,request,new SimulationService.ReviewInput(true,new BigDecimal("100.00"),""));
            rejected("ALREADY_REVIEWED",()->service.review(admin,request,new SimulationService.ReviewInput(true,new BigDecimal("100.00"),"")));
            assertEquals(new BigDecimal("100.00"),new BigDecimal(wallet(email).cash()));
            rejected("PRICE_UNAVAILABLE",()->trade(email,order(PortfolioService.Type.BUY,"1"),quote(Instant.now().minusSeconds(601))));
            Callable<Boolean> buy=()->{try{trade(email,order(PortfolioService.Type.BUY,"1"),quote(Instant.now()));return true;}catch(PortfolioService.Rejected e){assertTrue(e.code.equals("INSUFFICIENT_CASH")||e.code.equals("WALLET_CHANGED"));return false;}};
            var a=pool.submit(buy);var b=pool.submit(buy);
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(0,new BigDecimal(wallet(email).cash()).signum());
            rejected("OVERSELL",()->trade(email,order(PortfolioService.Type.SELL,"2"),quote(Instant.now())));
            trade(email,order(PortfolioService.Type.SELL,"1"),quote(Instant.now()));
            assertEquals(new BigDecimal("100.00"),new BigDecimal(wallet(email).cash()));
            assertTrue(wallet(other).trades().isEmpty());
            service.request(email,new SimulationService.FundingInput(BigDecimal.ONE,"Practice trading"));
            long rejectedRequest=wallet(email).requests().getFirst().id();service.review(admin,rejectedRequest,new SimulationService.ReviewInput(false,null,"Not needed"));
            assertEquals(new BigDecimal("100.00"),new BigDecimal(wallet(email).cash()));
            assertEquals("REJECTED",wallet(email).requests().getFirst().status());
            assertEquals(2,wallet(email).trades().size());
        } finally { pool.shutdownNow();clean(id);clean(otherId);clean(adminId); }
    }
}
