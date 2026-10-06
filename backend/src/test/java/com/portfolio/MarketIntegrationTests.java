package com.portfolio;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketIntegrationTests {
    static final AtomicInteger calls=new AtomicInteger();
    static volatile int providerStatus=200;
    static volatile String providerBody;
    static volatile String query;
    static final HttpServer provider;
    static {
        try {
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            provider.createContext("/price",exchange->{
                calls.incrementAndGet();query=exchange.getRequestURI().getQuery();
                byte[] bytes=providerBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(providerStatus,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });provider.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("market.endpoint",()->"http://127.0.0.1:"+provider.getAddress().getPort()+"/price");
        r.add("market.refresh-seconds",()->1);r.add("market.retry-seconds",()->1);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    HttpResponse<String> get(HttpClient client,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void pricesCacheValuationAndFailures() throws Exception {
        String email="market-test-"+UUID.randomUUID()+"@example.invalid";
        var backup=jdbc.queryForList("SELECT * FROM market_price WHERE quote_currency='MYR'");
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        try {
            jdbc.update("DELETE FROM market_price WHERE quote_currency='MYR'");
            jdbc.update("INSERT INTO app_user (email,password_hash) VALUES (?,?)",email,passwords.encode("Local-test-password-42"));
            jdbc.update("INSERT INTO portfolio_transaction (user_id,asset_symbol,transaction_type,quantity,unit_price,fee,occurred_at) SELECT id,'BTC','BUY',0.5,100,2,'2026-10-01' FROM app_user WHERE email=?",email);
            assertEquals(401,get(client,"/api/portfolio/view").statusCode());
            var csrf=json.readTree(get(client,"/api/auth/csrf").body());
            assertEquals(204,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                .header("Content-Type","application/x-www-form-urlencoded").header(csrf.get("headerName").asText(),csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("email="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password=Local-test-password-42")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            long source=Instant.now().getEpochSecond();
            providerBody="{\"bitcoin\":{\"myr\":200.123456789123,\"last_updated_at\":"+source+"},\"ethereum\":{\"myr\":100,\"last_updated_at\":"+source+"},\"solana\":{\"myr\":50,\"last_updated_at\":"+source+"}}";
            var response=get(client,"/api/portfolio/view");assertEquals(200,response.statusCode(),response.body());var view=json.readTree(response.body());
            assertEquals("LIVE",view.get("market").get("status").asText());
            assertEquals("MYR",view.get("market").get("currency").asText());
            assertEquals(0,new BigDecimal("100.0617283945615").compareTo(new BigDecimal(view.get("valuation").get("marketValue").asText())));
            assertEquals(0,new BigDecimal("48.0617283945615").compareTo(new BigDecimal(view.get("valuation").get("unrealizedProfit").asText())));
            assertTrue(query.contains("vs_currencies=myr"));assertTrue(query.contains("include_last_updated_at=true"));
            int fetched=calls.get();get(client,"/api/portfolio/view");assertEquals(fetched,calls.get());
            jdbc.update("UPDATE market_price SET source_updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE quote_currency='MYR'");
            assertEquals("STALE",json.readTree(get(client,"/api/portfolio/view").body()).get("market").get("status").asText());
            Thread.sleep(1100);providerStatus=429;providerBody="{}";
            view=json.readTree(get(client,"/api/portfolio/view").body());assertEquals("RATE_LIMITED",view.get("market").get("issue").asText());
            assertFalse(view.get("valuation").get("marketValue").isNull());
            fetched=calls.get();get(client,"/api/portfolio/view");assertEquals(fetched,calls.get());
            Thread.sleep(1100);providerStatus=200;providerBody="{\"bitcoin\":{\"myr\":999,\"last_updated_at\":"+source+"}}";
            view=json.readTree(get(client,"/api/portfolio/view").body());assertEquals("INVALID_RESPONSE",view.get("market").get("issue").asText());
            assertEquals(0,new BigDecimal("200.123456789123").compareTo(jdbc.queryForObject("SELECT price FROM market_price WHERE asset_symbol='BTC' AND quote_currency='MYR'",BigDecimal.class)));
            jdbc.update("DELETE FROM market_price WHERE asset_symbol='BTC' AND quote_currency='MYR'");
            view=json.readTree(get(client,"/api/portfolio/view").body());assertEquals("UNAVAILABLE",view.get("market").get("status").asText());
            assertTrue(view.get("valuation").get("marketValue").isNull());assertTrue(view.get("valuation").get("unrealizedProfit").isNull());
            jdbc.update("DELETE FROM portfolio_transaction WHERE user_id=(SELECT id FROM app_user WHERE email=?)",email);
            view=json.readTree(get(client,"/api/portfolio/view").body());assertEquals("0",view.get("valuation").get("marketValue").asText());
        } finally {
            jdbc.update("DELETE FROM portfolio_transaction WHERE user_id=(SELECT id FROM app_user WHERE email=?)",email);
            jdbc.update("DELETE FROM app_user WHERE email=?",email);
            jdbc.update("DELETE FROM market_price WHERE quote_currency='MYR'");
            for(var row:backup)jdbc.update("INSERT INTO market_price (asset_symbol,quote_currency,price,provider,fetched_at,source_updated_at) VALUES (?,?,?,?,?,?)",row.get("asset_symbol"),row.get("quote_currency"),row.get("price"),row.get("provider"),row.get("fetched_at"),row.get("source_updated_at"));
            provider.stop(0);
        }
    }
}
