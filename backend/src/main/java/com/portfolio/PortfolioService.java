package com.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {
    static final MathContext MC = MathContext.DECIMAL128;
    enum Type { BUY, SELL }
    public record Input(@NotNull @Pattern(regexp="BTC|ETH|SOL") String asset,
        @NotNull Type type,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=12) BigDecimal quantity,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=12) BigDecimal unitPrice,
        @NotNull @DecimalMin("0") @Digits(integer=16,fraction=12) BigDecimal fee,
        @NotNull @PastOrPresent Instant occurredAt) {}
    record Trade(long id, String asset, Type type, BigDecimal quantity, BigDecimal unitPrice,
                 BigDecimal fee, Instant occurredAt, String currency) {}
    public record Transaction(long id, String asset, Type type, String quantity, String unitPrice,
        String fee, Instant occurredAt, String currency) {}
    public record Holding(String asset, String quantity, String costBasis, String averageCost, String realizedProfit) {}
    public record Summary(String currency, String costBasis, String realizedProfit, List<Holding> holdings, int transactionCount) {}
    public record View(List<Transaction> trades, Summary summary) {}
    static class Position {
        BigDecimal quantity=BigDecimal.ZERO, cost=BigDecimal.ZERO, realized=BigDecimal.ZERO;
    }
    public static class Rejected extends RuntimeException {
        final HttpStatus status; final String code;
        Rejected(HttpStatus status,String code) { super(code); this.status=status; this.code=code; }
    }
    private final JdbcTemplate jdbc;
    public PortfolioService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private long user(String email, boolean lock) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email=?"+(lock?" FOR UPDATE":""),Long.class,email);
    }
    private List<Trade> trades(long userId) {
        return jdbc.query("SELECT * FROM portfolio_transaction WHERE user_id=? AND deleted_at IS NULL ORDER BY occurred_at,id",
            (rs,row)->new Trade(rs.getLong("id"),rs.getString("asset_symbol"),Type.valueOf(rs.getString("transaction_type")),
                rs.getBigDecimal("quantity"),rs.getBigDecimal("unit_price"),rs.getBigDecimal("fee"),
                rs.getTimestamp("occurred_at").toInstant(),rs.getString("quote_currency")),userId);
    }
    static Map<String,Position> replay(List<Trade> trades) {
        Map<String,Position> positions=new TreeMap<>();
        for (Trade trade:trades) {
            if (!trade.currency().equals("MYR")) throw new Rejected(HttpStatus.CONFLICT,"UNSUPPORTED_HISTORICAL_CURRENCY");
            Position p=positions.computeIfAbsent(trade.asset(), key->new Position());
            if(trade.type()==Type.BUY) {
                p.quantity=p.quantity.add(trade.quantity());
                p.cost=p.cost.add(trade.quantity().multiply(trade.unitPrice())).add(trade.fee());
            } else {
                if(p.quantity.compareTo(trade.quantity())<0) throw new Rejected(HttpStatus.CONFLICT,"OVERSELL");
                BigDecimal removed=p.quantity.compareTo(trade.quantity())==0?p.cost:
                    p.cost.multiply(trade.quantity()).divide(p.quantity,MC);
                p.realized=p.realized.add(trade.quantity().multiply(trade.unitPrice()).subtract(trade.fee()).subtract(removed));
                p.quantity=p.quantity.subtract(trade.quantity());
                p.cost=p.cost.subtract(removed);
            }
        }
        return positions;
    }
    @Transactional(readOnly=true)
    public List<Transaction> history(String email) {
        var result=new ArrayList<>(trades(user(email,false)).stream().map(t->new Transaction(t.id(),t.asset(),t.type(),
            t.quantity().toPlainString(),t.unitPrice().toPlainString(),t.fee().toPlainString(),t.occurredAt(),t.currency())).toList());
        Collections.reverse(result); return result;
    }
    @Transactional(readOnly=true)
    public View view(String email) { return new View(history(email),summary(email)); }
    @Transactional(readOnly=true)
    public Summary summary(String email) {
        var records=trades(user(email,false)); var positions=replay(records);
        List<Holding> holdings=new ArrayList<>();
        BigDecimal cost=BigDecimal.ZERO, realized=BigDecimal.ZERO;
        for(var entry:positions.entrySet()) {
            Position p=entry.getValue(); cost=cost.add(p.cost); realized=realized.add(p.realized);
            holdings.add(new Holding(entry.getKey(),p.quantity.toPlainString(),p.cost.toPlainString(),
                p.quantity.signum()==0?"0":p.cost.divide(p.quantity,MC).toPlainString(),p.realized.toPlainString()));
        }
        return new Summary("MYR",cost.toPlainString(),realized.toPlainString(),holdings,records.size());
    }
    private void validateDate(Input input) {
        if(input.occurredAt().isBefore(Instant.parse("1000-01-01T00:00:00Z")))
            throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_INPUT");
    }
    @Transactional
    public long create(String email,Input input) {
        validateDate(input); long owner=user(email,true);
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO portfolio_transaction (user_id,asset_symbol,transaction_type,quantity,unit_price,fee,occurred_at,quote_currency) VALUES (?,?,?,?,?,?,?,'MYR')",new String[]{"id"});
            statement.setLong(1,owner); statement.setString(2,input.asset()); statement.setString(3,input.type().name());
            statement.setBigDecimal(4,input.quantity()); statement.setBigDecimal(5,input.unitPrice()); statement.setBigDecimal(6,input.fee());
            statement.setTimestamp(7,Timestamp.from(input.occurredAt())); return statement;
        },keys);
        replay(trades(owner)); return Objects.requireNonNull(keys.getKey()).longValue();
    }
    @Transactional
    public void edit(String email,long id,Input input) {
        validateDate(input); long owner=user(email,true);
        int changed=jdbc.update("UPDATE portfolio_transaction SET asset_symbol=?,transaction_type=?,quantity=?,unit_price=?,fee=?,occurred_at=?,quote_currency='MYR' WHERE id=? AND user_id=? AND deleted_at IS NULL",
            input.asset(),input.type().name(),input.quantity(),input.unitPrice(),input.fee(),Timestamp.from(input.occurredAt()),id,owner);
        if(changed==0) throw new Rejected(HttpStatus.NOT_FOUND,"NOT_FOUND");
        replay(trades(owner));
    }
    @Transactional
    public void delete(String email,long id) {
        long owner=user(email,true);
        if(jdbc.update("UPDATE portfolio_transaction SET deleted_at=CURRENT_TIMESTAMP(6) WHERE id=? AND user_id=? AND deleted_at IS NULL",id,owner)==0)
            throw new Rejected(HttpStatus.NOT_FOUND,"NOT_FOUND");
        replay(trades(owner));
    }
    @Transactional
    public void restore(String email,long id) {
        long owner=user(email,true);
        if(jdbc.update("UPDATE portfolio_transaction SET deleted_at=NULL WHERE id=? AND user_id=? AND deleted_at IS NOT NULL",id,owner)==0)
            throw new Rejected(HttpStatus.NOT_FOUND,"NOT_FOUND");
        replay(trades(owner));
    }
}
