package com.portfolio;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MarketService {
    public record Quote(String asset, String price, Instant sourceUpdatedAt, Instant fetchedAt, String status) {}
    public record Market(String currency, String provider, String status, String issue, Instant checkedAt, List<Quote> quotes) {}
    public record ValuedHolding(String asset, String marketValue, String unrealizedProfit) {}
    public record Valuation(String marketValue, String unrealizedProfit, List<ValuedHolding> holdings) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final URI endpoint;
    private final String apiKey;
    private final long refreshSeconds, staleSeconds, retrySeconds;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private Instant nextAttempt=Instant.EPOCH, checkedAt;
    private String issue;
    public MarketService(JdbcTemplate jdbc, PlatformTransactionManager manager,
        @Value("${market.endpoint:https://api.coingecko.com/api/v3/simple/price}") String endpoint,
        @Value("${market.api-key:}") String apiKey,
        @Value("${market.refresh-seconds:120}") long refreshSeconds,
        @Value("${market.stale-seconds:600}") long staleSeconds,
        @Value("${market.retry-seconds:60}") long retrySeconds) {
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);this.endpoint=URI.create(endpoint);this.apiKey=apiKey;
        this.refreshSeconds=refreshSeconds;this.staleSeconds=staleSeconds;this.retrySeconds=retrySeconds;
    }
    // One shared fetch for all accounts; failures also have a cooldown.
    public synchronized Market snapshot() {
        Instant now=Instant.now();
        if(!now.isBefore(nextAttempt)) {
            checkedAt=now;
            try {
                var assets=jdbc.query("SELECT symbol,market_provider_id FROM supported_asset ORDER BY symbol",(rs,n)->Map.entry(rs.getString(1),rs.getString(2)));
                String ids=String.join(",",assets.stream().map(Map.Entry::getValue).toList());
                var request=HttpRequest.newBuilder(URI.create(endpoint+"?ids="+ids+"&vs_currencies=myr&include_last_updated_at=true&precision=12"))
                    .timeout(Duration.ofSeconds(6)).header("Accept","application/json").header("User-Agent","PortfolioLearningProject/1.0");
                if(!apiKey.isBlank())request.header("x-cg-demo-api-key",apiKey);
                var response=client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200)throw new IllegalStateException(response.statusCode()==429?"RATE_LIMITED":response.statusCode()==401||response.statusCode()==403?"ACCESS_DENIED":"PROVIDER_UNAVAILABLE");
                var root=json.readTree(response.body());
                List<Quote> incoming=new ArrayList<>();
                for(var asset:assets) {
                    var node=root.get(asset.getValue());
                    if(node==null||!node.has("myr")||!node.get("myr").isNumber()||!node.has("last_updated_at")||!node.get("last_updated_at").isIntegralNumber())throw new IllegalStateException("INVALID_RESPONSE");
                    BigDecimal price=new BigDecimal(node.get("myr").asText()).setScale(12,java.math.RoundingMode.HALF_UP);
                    Instant source=Instant.ofEpochSecond(node.get("last_updated_at").asLong());
                    if(price.signum()<=0||price.precision()-price.scale()>16||price.scale()>12||source.isAfter(now.plusSeconds(60))||source.isBefore(Instant.parse("2009-01-01T00:00:00Z")))throw new IllegalStateException("INVALID_RESPONSE");
                    incoming.add(new Quote(asset.getKey(),price.toPlainString(),source,now,"LIVE"));
                }
                // Validate the complete response before replacing any cached prices.
                transaction.executeWithoutResult(status->{for(var q:incoming)jdbc.update("INSERT INTO market_price (asset_symbol,quote_currency,price,provider,fetched_at,source_updated_at) VALUES (?,'MYR',?,'CoinGecko',?,?) ON DUPLICATE KEY UPDATE price=VALUES(price),provider=VALUES(provider),fetched_at=VALUES(fetched_at),source_updated_at=VALUES(source_updated_at)",q.asset(),new BigDecimal(q.price()),Timestamp.from(q.fetchedAt()),Timestamp.from(q.sourceUpdatedAt()));});
                issue=null;nextAttempt=now.plusSeconds(refreshSeconds);
            } catch(InterruptedException e) {Thread.currentThread().interrupt();issue="PROVIDER_UNAVAILABLE";nextAttempt=now.plusSeconds(retrySeconds);}
            catch(Exception e) {issue=e instanceof IllegalStateException?e.getMessage():"PROVIDER_UNAVAILABLE";nextAttempt=now.plusSeconds(retrySeconds);}
        }
        Instant at=Instant.now();
        var quotes=jdbc.query("SELECT s.symbol,p.price,p.source_updated_at,p.fetched_at FROM supported_asset s LEFT JOIN market_price p ON p.asset_symbol=s.symbol AND p.quote_currency='MYR' AND p.provider='CoinGecko' ORDER BY s.symbol",(rs,n)->{
            var source=rs.getTimestamp("source_updated_at");var fetched=rs.getTimestamp("fetched_at");var price=rs.getBigDecimal("price");
            String state=price==null||source==null?"UNAVAILABLE":source.toInstant().isBefore(at.minusSeconds(staleSeconds))||fetched.toInstant().isBefore(at.minusSeconds(staleSeconds))?"STALE":"LIVE";
            return new Quote(rs.getString("symbol"),price==null?null:price.toPlainString(),source==null?null:source.toInstant(),fetched==null?null:fetched.toInstant(),state);
        });
        String state=quotes.stream().anyMatch(q->q.status().equals("UNAVAILABLE"))?"UNAVAILABLE":quotes.stream().anyMatch(q->q.status().equals("STALE"))?"STALE":"LIVE";
        return new Market("MYR","CoinGecko",state,issue,checkedAt,quotes);
    }
    public Valuation value(PortfolioService.Summary summary,Market market) {
        Map<String,Quote> prices=new HashMap<>();market.quotes().forEach(q->prices.put(q.asset(),q));
        List<ValuedHolding> holdings=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;boolean complete=true;
        for(var h:summary.holdings()) {
            BigDecimal quantity=new BigDecimal(h.quantity());if(quantity.signum()==0)continue;
            var quote=prices.get(h.asset());
            if(quote==null||quote.price()==null||quote.status().equals("UNAVAILABLE")){complete=false;holdings.add(new ValuedHolding(h.asset(),null,null));continue;}
            BigDecimal value=quantity.multiply(new BigDecimal(quote.price()));total=total.add(value);
            holdings.add(new ValuedHolding(h.asset(),value.toPlainString(),value.subtract(new BigDecimal(h.costBasis())).toPlainString()));
        }
        return new Valuation(complete?total.toPlainString():null,complete?total.subtract(new BigDecimal(summary.costBasis())).toPlainString():null,holdings);
    }
}
