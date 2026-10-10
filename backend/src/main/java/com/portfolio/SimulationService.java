package com.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulationService {
    public record FundingInput(
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer=7,fraction=2) BigDecimal amount,
        @NotBlank @Size(max=500) String purpose) {}
    public record ReviewInput(@NotNull Boolean approve,
        @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer=7,fraction=2) BigDecimal amount,
        @NotNull @Size(max=500) String note) {}
    public record GrantInput(@Positive long userId,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000") @Digits(integer=7,fraction=2) BigDecimal amount,
        @NotBlank @Size(max=500) String note, @NotNull UUID operationId) {}
    public record Order(@NotNull @Pattern(regexp="BTC|ETH|SOL") String asset,
        @NotNull PortfolioService.Type type,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=12) BigDecimal quantity) {}
    public record Execution(@NotNull UUID quoteId) {}
    public record Request(long id,String email,String amount,String purpose,String status,String approvedAmount,
        String reviewNote,String reviewedBy,Instant createdAt,Instant reviewedAt) {}
    public record Credit(long id,String email,String amount,String source,String note,String adminEmail,Instant createdAt) {}
    public record Holding(String asset,String quantity,String costBasis,String averageCost,String marketValue,String unrealizedProfit) {}
    public record Trade(long id,String asset,String type,String quantity,String price,String amount,Instant createdAt) {}
    public record Wallet(boolean admin,String cash,String funded,String marketValue,String totalAssets,
        String realizedProfit,String unrealizedProfit,String totalProfit,List<Holding> holdings,
        List<Request> requests,List<Credit> credits,List<Trade> trades,String valuationStatus,MarketService.Market market) {}
    public record AccountOverview(long id,String email,String cash,String funded,int pendingRequests,Instant createdAt) {}
    public record AccountPage(List<AccountOverview> accounts,int page,boolean more) {}
    public record Preview(String id,String asset,String type,String quantity,String price,String amount,String fee,
        String cashBefore,String cashAfter,Instant sourceUpdatedAt,Instant fetchedAt,Instant expiresAt) {}
    private static class Position {
        BigDecimal quantity=BigDecimal.ZERO,cost=BigDecimal.ZERO,realized=BigDecimal.ZERO;
    }
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
    private void lockUser(long id) {
        if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,id).isEmpty())
            reject(HttpStatus.NOT_FOUND,"NOT_FOUND");
    }
    private BigDecimal funded(long id) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM simulation_funding_credit WHERE user_id=?",BigDecimal.class,id);
    }
    private BigDecimal cash(long id) {
        BigDecimal spent=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN transaction_type='BUY' THEN cash_amount ELSE -cash_amount END),0) FROM simulated_trade WHERE user_id=?",BigDecimal.class,id);
        return funded(id).subtract(spent);
    }
    private BigDecimal held(long id,String asset) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN transaction_type='BUY' THEN quantity ELSE -quantity END),0) FROM simulated_trade WHERE user_id=? AND asset_symbol=?",BigDecimal.class,id,asset);
    }
    private List<Request> requests(Long owner) {
        return jdbc.query("SELECT f.*,u.email,a.email admin_email FROM funding_request f JOIN app_user u ON u.id=f.user_id LEFT JOIN app_user a ON a.id=f.reviewed_by"+
            (owner==null?"":" WHERE f.user_id=?")+(owner==null?" ORDER BY (f.status='PENDING') DESC,f.id DESC LIMIT 100":" ORDER BY f.id DESC LIMIT 100"),
            (rs,n)->{
                BigDecimal amount=rs.getBigDecimal("approved_amount");Timestamp reviewed=rs.getTimestamp("reviewed_at");
                return new Request(rs.getLong("id"),rs.getString("email"),rs.getBigDecimal("amount").toPlainString(),rs.getString("purpose"),
                    rs.getString("status"),amount==null?null:amount.toPlainString(),rs.getString("review_note"),rs.getString("admin_email"),
                    rs.getTimestamp("created_at").toInstant(),reviewed==null?null:reviewed.toInstant());
            },owner==null?new Object[]{}:new Object[]{owner});
    }
    private List<Credit> credits(Long owner) {
        return jdbc.query("SELECT c.*,u.email,a.email admin_email FROM simulation_funding_credit c JOIN app_user u ON u.id=c.user_id LEFT JOIN app_user a ON a.id=c.admin_id"+
            (owner==null?"":" WHERE c.user_id=?")+" ORDER BY c.id DESC LIMIT 100",
            (rs,n)->new Credit(rs.getLong("id"),rs.getString("email"),rs.getBigDecimal("amount").toPlainString(),rs.getString("source_type"),
                rs.getString("note"),rs.getString("admin_email"),rs.getTimestamp("created_at").toInstant()),owner==null?new Object[]{}:new Object[]{owner});
    }
    private List<Trade> trades(long id) {
        return jdbc.query("SELECT * FROM simulated_trade WHERE user_id=? ORDER BY id",
            (rs,n)->new Trade(rs.getLong("id"),rs.getString("asset_symbol"),rs.getString("transaction_type"),rs.getBigDecimal("quantity").toPlainString(),
                rs.getBigDecimal("unit_price").toPlainString(),rs.getBigDecimal("cash_amount").toPlainString(),rs.getTimestamp("created_at").toInstant()),id);
    }
    @Transactional(readOnly=true)
    public Wallet wallet(String email,MarketService.Market market) {
        long id=user(email,false);
        List<Trade> records=trades(id);
        Map<String,Position> positions=new TreeMap<>();
        for(Trade t:records) {
            Position p=positions.computeIfAbsent(t.asset(),asset->new Position());
            BigDecimal quantity=new BigDecimal(t.quantity()),amount=new BigDecimal(t.amount());
            if(t.type().equals("BUY")) { p.quantity=p.quantity.add(quantity);p.cost=p.cost.add(amount); }
            else {
                if(p.quantity.compareTo(quantity)<0)reject(HttpStatus.CONFLICT,"OVERSELL");
                BigDecimal removed=p.quantity.compareTo(quantity)==0?p.cost:p.cost.multiply(quantity).divide(p.quantity,MathContext.DECIMAL128);
                p.realized=p.realized.add(amount.subtract(removed));p.quantity=p.quantity.subtract(quantity);p.cost=p.cost.subtract(removed);
            }
        }
        Map<String,MarketService.Quote> prices=new HashMap<>();market.quotes().forEach(q->prices.put(q.asset(),q));
        List<Holding> holdings=new ArrayList<>();
        BigDecimal realized=BigDecimal.ZERO,unrealized=BigDecimal.ZERO,value=BigDecimal.ZERO;
        boolean complete=true,stale=false;
        for(var entry:positions.entrySet()) {
            Position p=entry.getValue();realized=realized.add(p.realized);
            if(p.quantity.signum()==0)continue;
            MarketService.Quote q=prices.get(entry.getKey());BigDecimal worth=null,gain=null;
            if(q==null||q.price()==null||q.status().equals("UNAVAILABLE"))complete=false;
            else { worth=p.quantity.multiply(new BigDecimal(q.price()));gain=worth.subtract(p.cost);value=value.add(worth);unrealized=unrealized.add(gain);stale|=!fresh(q); }
            holdings.add(new Holding(entry.getKey(),p.quantity.toPlainString(),p.cost.toPlainString(),p.cost.divide(p.quantity,MathContext.DECIMAL128).toPlainString(),
                worth==null?null:worth.toPlainString(),gain==null?null:gain.toPlainString()));
        }
        BigDecimal balance=cash(id),funding=funded(id);
        List<Trade> recent=new ArrayList<>(records.subList(Math.max(0,records.size()-100),records.size()));Collections.reverse(recent);
        return new Wallet(isAdmin(email),balance.toPlainString(),funding.toPlainString(),complete?value.toPlainString():null,
            complete?balance.add(value).toPlainString():null,realized.toPlainString(),complete?unrealized.toPlainString():null,
            complete?balance.add(value).subtract(funding).toPlainString():null,holdings,requests(id),credits(id),recent,
            !complete?"UNAVAILABLE":stale?"STALE":"LIVE",market);
    }
    public List<Request> adminRequests(String email) { requireAdmin(email);return requests(null); }
    public List<Credit> adminCredits(String email) { requireAdmin(email);return credits(null); }
    @Transactional(readOnly=true)
    public AccountPage accounts(String email,String search,int page) {
        requireAdmin(email);
        if(page<0||page>100000||search.length()>254)reject(HttpStatus.BAD_REQUEST,"INVALID_INPUT");
        String pattern="%"+search.trim().toLowerCase(Locale.ROOT).replace("=","==").replace("%","=%").replace("_","=_")+"%";
        List<AccountOverview> result=jdbc.query("SELECT u.id,u.email,u.created_at,COALESCE(f.amount,0) funded,COALESCE(f.amount,0)-COALESCE(t.spent,0) cash,COALESCE(r.pending,0) pending FROM app_user u "+
            "LEFT JOIN (SELECT user_id,SUM(amount) amount FROM simulation_funding_credit GROUP BY user_id) f ON f.user_id=u.id "+
            "LEFT JOIN (SELECT user_id,SUM(CASE WHEN transaction_type='BUY' THEN cash_amount ELSE -cash_amount END) spent FROM simulated_trade GROUP BY user_id) t ON t.user_id=u.id "+
            "LEFT JOIN (SELECT user_id,COUNT(*) pending FROM funding_request WHERE status='PENDING' GROUP BY user_id) r ON r.user_id=u.id "+
            "WHERE u.email LIKE ? ESCAPE '=' ORDER BY u.id LIMIT 51 OFFSET ?",
            (rs,n)->new AccountOverview(rs.getLong("id"),rs.getString("email"),rs.getBigDecimal("cash").toPlainString(),rs.getBigDecimal("funded").toPlainString(),
                rs.getInt("pending"),rs.getTimestamp("created_at").toInstant()),pattern,page*50);
        return new AccountPage(result.subList(0,Math.min(result.size(),50)),page,result.size()>50);
    }
    @Transactional
    public void request(String email,FundingInput input) {
        long id=user(email,true);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM funding_request WHERE user_id=? AND status='PENDING'",Integer.class,id)>0)
            reject(HttpStatus.CONFLICT,"REQUEST_PENDING");
        jdbc.update("INSERT INTO funding_request (user_id,amount,purpose) VALUES (?,?,?)",id,input.amount(),input.purpose().trim());
    }
    @Transactional
    public void review(String email,long requestId,ReviewInput input) {
        requireAdmin(email);
        var owners=jdbc.queryForList("SELECT user_id FROM funding_request WHERE id=?",Long.class,requestId);
        if(owners.isEmpty())reject(HttpStatus.NOT_FOUND,"NOT_FOUND");
        long owner=owners.getFirst();lockUser(owner);
        var row=jdbc.queryForMap("SELECT status,amount FROM funding_request WHERE id=? FOR UPDATE",requestId);
        if(!"PENDING".equals(row.get("status")))reject(HttpStatus.CONFLICT,"ALREADY_REVIEWED");
        BigDecimal requested=(BigDecimal)row.get("amount"),approved=input.approve()?input.amount():null;
        String note=input.note().trim();
        if(input.approve()&&approved==null)reject(HttpStatus.BAD_REQUEST,"INVALID_INPUT");
        if((!input.approve()||requested.compareTo(approved)!=0)&&note.isBlank())reject(HttpStatus.BAD_REQUEST,"REASON_REQUIRED");
        long actor=user(email,false);
        jdbc.update("UPDATE funding_request SET status=?,approved_amount=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP(6),reviewed_by=? WHERE id=?",
            input.approve()?"APPROVED":"REJECTED",approved,note,actor,requestId);
        if(input.approve())jdbc.update("INSERT INTO simulation_funding_credit (user_id,admin_id,request_id,source_type,amount,note) VALUES (?,?,?,'REQUEST',?,?)",
            owner,actor,requestId,approved,note);
    }
    @Transactional
    public void grant(String email,GrantInput input) {
        requireAdmin(email);long actor=user(email,false);lockUser(input.userId());
        var existing=jdbc.queryForList("SELECT user_id,amount,note FROM simulation_funding_credit WHERE admin_id=? AND operation_id=?",actor,input.operationId().toString());
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(((Number)row.get("user_id")).longValue()!=input.userId()||((BigDecimal)row.get("amount")).compareTo(input.amount())!=0||!row.get("note").equals(input.note().trim()))
                reject(HttpStatus.CONFLICT,"DUPLICATE_OPERATION");
            return;
        }
        try {
            jdbc.update("INSERT INTO simulation_funding_credit (user_id,admin_id,source_type,amount,note,operation_id) VALUES (?,?,'DIRECT',?,?,?)",
                input.userId(),actor,input.amount(),input.note().trim(),input.operationId().toString());
        } catch(DuplicateKeyException duplicate) { reject(HttpStatus.CONFLICT,"DUPLICATE_OPERATION"); }
    }
    private static boolean fresh(MarketService.Quote q) {
        Instant cutoff=Instant.now().minusSeconds(600);
        return q!=null&&"LIVE".equals(q.status())&&q.price()!=null&&q.sourceUpdatedAt()!=null&&q.fetchedAt()!=null&&
            !q.sourceUpdatedAt().isBefore(cutoff)&&!q.fetchedAt().isBefore(cutoff);
    }
    private static MarketService.Quote price(MarketService.Market market,String asset) {
        return market.quotes().stream().filter(q->q.asset().equals(asset)).findFirst().orElse(null);
    }
    private Preview previewRow(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Preview(rs.getString("id"),rs.getString("asset_symbol"),rs.getString("transaction_type"),rs.getBigDecimal("quantity").toPlainString(),
            rs.getBigDecimal("unit_price").toPlainString(),rs.getBigDecimal("cash_amount").toPlainString(),"0.00",rs.getBigDecimal("cash_before").toPlainString(),
            rs.getBigDecimal("cash_after").toPlainString(),rs.getTimestamp("source_updated_at").toInstant(),rs.getTimestamp("fetched_at").toInstant(),rs.getTimestamp("expires_at").toInstant());
    }
    @Transactional
    public Preview preview(String email,Order order,MarketService.Market market) {
        long id=user(email,true);MarketService.Quote q=price(market,order.asset());
        if(!fresh(q))reject(HttpStatus.CONFLICT,"PRICE_UNAVAILABLE");
        BigDecimal unitPrice=new BigDecimal(q.price());
        BigDecimal amount=order.quantity().multiply(unitPrice).setScale(2,order.type()==PortfolioService.Type.BUY?RoundingMode.CEILING:RoundingMode.FLOOR);
        if(amount.signum()<=0||amount.precision()-amount.scale()>26)reject(HttpStatus.BAD_REQUEST,"TRADE_TOO_SMALL_OR_LARGE");
        BigDecimal before=cash(id),after;
        if(order.type()==PortfolioService.Type.BUY) {
            if(before.compareTo(amount)<0)reject(HttpStatus.CONFLICT,"INSUFFICIENT_CASH");after=before.subtract(amount);
        } else {
            if(held(id,order.asset()).compareTo(order.quantity())<0)reject(HttpStatus.CONFLICT,"OVERSELL");after=before.add(amount);
        }
        // Only expired, unexecuted previews are pruned; trades keep their confirmation evidence.
        jdbc.update("DELETE q FROM simulation_trade_quote q LEFT JOIN simulated_trade t ON t.quote_id=q.id WHERE q.user_id=? AND q.expires_at<? AND t.id IS NULL",id,Timestamp.from(Instant.now().minusSeconds(86400)));
        String token=UUID.randomUUID().toString();Instant expires=Instant.now().plusSeconds(60);
        jdbc.update("INSERT INTO simulation_trade_quote (id,user_id,asset_symbol,transaction_type,quantity,unit_price,cash_amount,cash_before,cash_after,source_updated_at,fetched_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            token,id,order.asset(),order.type().name(),order.quantity(),unitPrice,amount,before,after,Timestamp.from(q.sourceUpdatedAt()),Timestamp.from(q.fetchedAt()),Timestamp.from(expires));
        return new Preview(token,order.asset(),order.type().name(),order.quantity().toPlainString(),unitPrice.toPlainString(),amount.toPlainString(),"0.00",
            before.toPlainString(),after.toPlainString(),q.sourceUpdatedAt(),q.fetchedAt(),expires);
    }
    @Transactional
    public void trade(String email,Execution input,MarketService.Market market) {
        long id=user(email,true);String token=input.quoteId().toString();
        // Retrying one confirmed order cannot buy or sell twice.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE user_id=? AND quote_id=?",Integer.class,id,token)>0)return;
        var previews=jdbc.query("SELECT * FROM simulation_trade_quote WHERE id=? AND user_id=? FOR UPDATE",this::previewRow,token,id);
        if(previews.isEmpty())reject(HttpStatus.NOT_FOUND,"NOT_FOUND");Preview p=previews.getFirst();
        if(!Instant.now().isBefore(p.expiresAt()))reject(HttpStatus.CONFLICT,"QUOTE_EXPIRED");
        MarketService.Quote q=price(market,p.asset());if(!fresh(q))reject(HttpStatus.CONFLICT,"PRICE_UNAVAILABLE");
        if(new BigDecimal(q.price()).compareTo(new BigDecimal(p.price()))!=0)reject(HttpStatus.CONFLICT,"PRICE_CHANGED");
        if(cash(id).compareTo(new BigDecimal(p.cashBefore()))!=0)reject(HttpStatus.CONFLICT,"WALLET_CHANGED");
        BigDecimal quantity=new BigDecimal(p.quantity());
        if(p.type().equals("SELL")&&held(id,p.asset()).compareTo(quantity)<0)reject(HttpStatus.CONFLICT,"OVERSELL");
        jdbc.update("INSERT INTO simulated_trade (user_id,asset_symbol,transaction_type,quantity,unit_price,cash_amount,quote_id) VALUES (?,?,?,?,?,?,?)",
            id,p.asset(),p.type(),quantity,new BigDecimal(p.price()),new BigDecimal(p.amount()),token);
    }
}
