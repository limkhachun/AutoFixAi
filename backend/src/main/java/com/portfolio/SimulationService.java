package com.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulationService {
    public record FundingInput(@NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer=7,fraction=2) BigDecimal amount) {}
    public record Order(@NotNull @Pattern(regexp="BTC|ETH|SOL") String asset,
        @NotNull PortfolioService.Type type,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=12) BigDecimal quantity) {}
    public record Request(long id,String email,String amount,String status,Instant createdAt) {}
    public record Holding(String asset,String quantity) {}
    public record Trade(long id,String asset,String type,String quantity,String price,String amount,Instant createdAt) {}
    public record Wallet(boolean admin,String cash,List<Holding> holdings,List<Request> requests,List<Trade> trades) {}
    private final JdbcTemplate jdbc;
    private final String adminEmail;
    public SimulationService(JdbcTemplate jdbc,@Value("${simulation.admin-email:}") String adminEmail) {
        this.jdbc=jdbc;this.adminEmail=adminEmail.trim().toLowerCase(Locale.ROOT);
    }
    public boolean isAdmin(String email) { return !adminEmail.isBlank() && adminEmail.equalsIgnoreCase(email); }
    void requireAdmin(String email) { if(!isAdmin(email))reject(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED"); }
    private static void reject(HttpStatus status,String code) { throw new PortfolioService.Rejected(status,code); }
    private long user(String email,boolean lock) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email=?"+(lock?" FOR UPDATE":""),Long.class,email);
    }
    private BigDecimal cash(long id) {
        BigDecimal grants=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM funding_request WHERE user_id=? AND status='APPROVED'",BigDecimal.class,id);
        BigDecimal spent=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN transaction_type='BUY' THEN cash_amount ELSE -cash_amount END),0) FROM simulated_trade WHERE user_id=?",BigDecimal.class,id);
        return grants.subtract(spent);
    }
    private List<Request> requests(Long owner) {
        return jdbc.query("SELECT f.*,u.email FROM funding_request f JOIN app_user u ON u.id=f.user_id"+(owner==null?"":" WHERE f.user_id=?")+(owner==null?" ORDER BY (f.status='PENDING') DESC,f.id DESC LIMIT 100":" ORDER BY f.id DESC LIMIT 100"),
            (rs,n)->new Request(rs.getLong("id"),rs.getString("email"),rs.getBigDecimal("amount").toPlainString(),rs.getString("status"),rs.getTimestamp("created_at").toInstant()),owner==null?new Object[]{}:new Object[]{owner});
    }
    @Transactional(readOnly=true)
    public Wallet wallet(String email) {
        long id=user(email,false);
        var holdings=jdbc.query("SELECT asset_symbol,SUM(CASE WHEN transaction_type='BUY' THEN quantity ELSE -quantity END) quantity FROM simulated_trade WHERE user_id=? GROUP BY asset_symbol",
            (rs,n)->new Holding(rs.getString(1),rs.getBigDecimal(2).toPlainString()),id);
        var trades=jdbc.query("SELECT * FROM simulated_trade WHERE user_id=? ORDER BY id DESC LIMIT 100",
            (rs,n)->new Trade(rs.getLong("id"),rs.getString("asset_symbol"),rs.getString("transaction_type"),rs.getBigDecimal("quantity").toPlainString(),rs.getBigDecimal("unit_price").toPlainString(),rs.getBigDecimal("cash_amount").toPlainString(),rs.getTimestamp("created_at").toInstant()),id);
        return new Wallet(isAdmin(email),cash(id).toPlainString(),holdings,requests(id),trades);
    }
    public List<Request> adminRequests(String email) { requireAdmin(email);return requests(null); }
    @Transactional
    public void request(String email,FundingInput input) {
        long id=user(email,true);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM funding_request WHERE user_id=? AND status='PENDING'",Integer.class,id)>0)
            reject(HttpStatus.CONFLICT,"REQUEST_PENDING");
        jdbc.update("INSERT INTO funding_request (user_id,amount) VALUES (?,?)",id,input.amount());
    }
    @Transactional
    public void review(String email,long requestId,boolean approve) {
        requireAdmin(email);
        var owners=jdbc.queryForList("SELECT user_id FROM funding_request WHERE id=?",Long.class,requestId);
        if(owners.isEmpty())reject(HttpStatus.NOT_FOUND,"NOT_FOUND");
        // Same account lock as trades: simultaneous approvals/orders cannot overspend or double-credit.
        jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,owners.getFirst());
        if(jdbc.update("UPDATE funding_request SET status=?,reviewed_at=CURRENT_TIMESTAMP(6),reviewed_by=? WHERE id=? AND status='PENDING'",
            approve?"APPROVED":"REJECTED",user(email,false),requestId)!=1)reject(HttpStatus.CONFLICT,"ALREADY_REVIEWED");
    }
    @Transactional
    public void trade(String email,Order order,MarketService.Quote quote) {
        if(quote==null||!quote.asset().equals(order.asset())||!"LIVE".equals(quote.status())||quote.price()==null||quote.fetchedAt()==null||quote.sourceUpdatedAt()==null||quote.sourceUpdatedAt().isBefore(Instant.now().minusSeconds(600))||quote.fetchedAt().isBefore(Instant.now().minusSeconds(600)))
            reject(HttpStatus.CONFLICT,"PRICE_UNAVAILABLE");
        long id=user(email,true);
        BigDecimal price=new BigDecimal(quote.price());
        // Conservative cent rounding prevents tiny repeated trades from manufacturing cash.
        BigDecimal amount=order.quantity().multiply(price).setScale(2,order.type()==PortfolioService.Type.BUY?RoundingMode.CEILING:RoundingMode.FLOOR);
        if(amount.signum()<=0||amount.precision()-amount.scale()>26)reject(HttpStatus.BAD_REQUEST,"TRADE_TOO_SMALL_OR_LARGE");
        if(order.type()==PortfolioService.Type.BUY) {
            if(cash(id).compareTo(amount)<0)reject(HttpStatus.CONFLICT,"INSUFFICIENT_CASH");
        } else {
            BigDecimal held=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN transaction_type='BUY' THEN quantity ELSE -quantity END),0) FROM simulated_trade WHERE user_id=? AND asset_symbol=?",BigDecimal.class,id,order.asset());
            if(held.compareTo(order.quantity())<0)reject(HttpStatus.CONFLICT,"OVERSELL");
        }
        jdbc.update("INSERT INTO simulated_trade (user_id,asset_symbol,transaction_type,quantity,unit_price,cash_amount) VALUES (?,?,?,?,?,?)",id,order.asset(),order.type().name(),order.quantity(),price,amount);
    }
}
