package com.portfolio;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortfolioIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    record TestAccount(String email,HttpClient client) {}
    private HttpResponse<String> get(HttpClient client,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> mutate(HttpClient client,String method,String path,String body,String contentType) throws Exception {
        var csrf=json.readTree(get(client,"/api/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .header("Content-Type",contentType).header(csrf.get("headerName").asText(),csrf.get("token").asText())
            .method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private TestAccount account() throws Exception {
        String email="portfolio-test-"+UUID.randomUUID()+"@example.invalid";
        jdbc.update("INSERT INTO app_user (email,password_hash) VALUES (?,?)",email,passwords.encode("Local-test-password-42"));
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(204,mutate(client,"POST","/api/auth/login","email="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password=Local-test-password-42","application/x-www-form-urlencoded").statusCode());
        return new TestAccount(email,client);
    }
    private void cleanup(TestAccount account) {
        jdbc.update("DELETE FROM portfolio_transaction WHERE user_id=(SELECT id FROM app_user WHERE email=?)",account.email());
        jdbc.update("DELETE FROM app_user WHERE email=?",account.email());
    }
    private String trade(String type,String quantity,String price,String fee,String date) {
        return String.format("{\"asset\":\"BTC\",\"type\":\"%s\",\"quantity\":\"%s\",\"unitPrice\":\"%s\",\"fee\":\"%s\",\"occurredAt\":\"%sT00:00:00Z\"}",type,quantity,price,fee,date);
    }
    private HttpResponse<String> change(TestAccount a,String method,String path,String body) throws Exception {return mutate(a.client(),method,path,body,"application/json");}
    private long create(TestAccount a,String body) throws Exception {
        var r=change(a,"POST","/api/transactions",body);assertEquals(201,r.statusCode(),r.body());return json.readTree(r.body()).get("id").asLong();
    }
    private void decimal(String expected,String actual) {assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual)));}
    private void summary(TestAccount a,String cost,String profit,String quantity) throws Exception {
        var r=get(a.client(),"/api/portfolio");assertEquals(200,r.statusCode(),r.body());var node=json.readTree(r.body());
        decimal(cost,node.get("costBasis").asText());decimal(profit,node.get("realizedProfit").asText());
        decimal(quantity,node.get("holdings").get(0).get("quantity").asText());
    }
    @Test void weightedAverageEditDeleteRestoreAndRollback() throws Exception {
        var a=account();
        try {
            long first=create(a,trade("BUY","1","100","2","2026-10-01"));
            create(a,trade("BUY","1","120","2","2026-10-02"));
            long sale=create(a,trade("SELL","0.5","140","1","2026-10-03"));
            summary(a,"168","13","1.5");
            assertEquals(409,change(a,"POST","/api/transactions",trade("SELL","2","140","0","2026-10-04")).statusCode());
            assertEquals(3,json.readTree(get(a.client(),"/api/transactions").body()).size());
            assertEquals(409,change(a,"PUT","/api/transactions/"+sale,trade("SELL","0.5","140","1","2026-09-30")).statusCode());
            summary(a,"168","13","1.5");
            assertEquals(204,change(a,"DELETE","/api/transactions/"+sale,"").statusCode());summary(a,"224","0","2");
            assertEquals(204,change(a,"POST","/api/transactions/"+sale+"/restore","").statusCode());summary(a,"168","13","1.5");
            assertEquals(204,change(a,"PUT","/api/transactions/"+sale,trade("SELL","2","140","1","2026-10-03")).statusCode());summary(a,"0","55","0");
            assertEquals(409,change(a,"DELETE","/api/transactions/"+first,"").statusCode());
            assertEquals(3,json.readTree(get(a.client(),"/api/transactions").body()).size());
            assertEquals(400,change(a,"POST","/api/transactions",trade("BUY","0","100","0","2026-10-01")).statusCode());
        }finally{cleanup(a);}
    }
    @Test void concurrentSalesCannotOversell() throws Exception {
        var a=account();var executor=Executors.newFixedThreadPool(2);
        try {
            create(a,trade("BUY","1","100","0","2026-10-01"));
            get(a.client(),"/api/auth/csrf");
            var gate=new CountDownLatch(1);
            Callable<Integer> sell=()->{gate.await();return change(a,"POST","/api/transactions",trade("SELL","0.75","120","0","2026-10-02")).statusCode();};
            var one=executor.submit(sell);var two=executor.submit(sell);gate.countDown();
            int statusOne=one.get(20,TimeUnit.SECONDS),statusTwo=two.get(20,TimeUnit.SECONDS);
            assertTrue((statusOne==201&&statusTwo==409)||(statusOne==409&&statusTwo==201));
            summary(a,"25","15","0.25");assertEquals(2,json.readTree(get(a.client(),"/api/transactions").body()).size());
        }finally{executor.shutdownNow();executor.awaitTermination(20,TimeUnit.SECONDS);cleanup(a);}
    }
}
