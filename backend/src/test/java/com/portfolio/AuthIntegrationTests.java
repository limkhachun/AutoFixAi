package com.portfolio;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String body, String contentType, boolean withCsrf) throws Exception {
        return mutate(client,path,body,contentType,withCsrf,"POST");
    }
    private HttpResponse<String> mutate(HttpClient client, String path, String body, String contentType, boolean withCsrf, String method) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type",contentType);
        if (withCsrf) {
            var csrf=get(client,"/api/auth/csrf");
            assertEquals(200,csrf.statusCode());
            var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrf.body());
            assertTrue(matcher.find());
            request.header("X-CSRF-TOKEN",matcher.group(1));
        }
        return client.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String registration(String email) {
        return "{\"email\":\""+email+"\",\"password\":\"Local-test-password-42\",\"language\":\"en\"}";
    }
    private String login(String email, String password) {
        return "email="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8);
    }

    @Test void csrfHashingSessionIsolationAndLogout() throws Exception {
        String emailA="auth-a-"+UUID.randomUUID()+"@example.invalid";
        String emailB="auth-b-"+UUID.randomUUID()+"@example.invalid";
        var a=client(); var b=client();
        try {
            assertEquals(401,get(a,"/api/auth/me").statusCode());
            assertEquals(403,post(a,"/api/auth/register",registration(emailA),"application/json",false).statusCode());
            assertEquals(201,post(a,"/api/auth/register",registration(emailA),"application/json",true).statusCode());
            String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email=?",String.class,emailA);
            assertNotEquals("Local-test-password-42",hash);
            assertTrue(encoder.matches("Local-test-password-42",hash));
            assertEquals(409,post(a,"/api/auth/register",registration(emailA),"application/json",true).statusCode());
            assertEquals(401,post(a,"/api/auth/login",login(emailA,"wrong-password"),"application/x-www-form-urlencoded",true).statusCode());
            assertEquals(204,post(a,"/api/auth/login",login(emailA,"Local-test-password-42"),"application/x-www-form-urlencoded",true).statusCode());
            assertEquals(201,post(b,"/api/auth/register",registration(emailB),"application/json",true).statusCode());
            assertEquals(204,post(b,"/api/auth/login",login(emailB,"Local-test-password-42"),"application/x-www-form-urlencoded",true).statusCode());
            long idB=jdbc.queryForObject("SELECT id FROM app_user WHERE email=?",Long.class,emailB);
            var me=get(a,"/api/auth/me?id="+idB);
            assertEquals(200,me.statusCode());
            assertTrue(me.body().contains(emailA));
            assertFalse(me.body().contains(emailB));
            assertFalse(me.body().contains("password"));
            assertTrue(get(b,"/api/auth/me").body().contains(emailB));
            String trade="{\"asset\":\"BTC\",\"type\":\"BUY\",\"quantity\":\"1\",\"unitPrice\":\"100\",\"fee\":\"2\",\"occurredAt\":\"2026-10-01T00:00:00Z\"}";
            assertEquals(403,post(a,"/api/transactions",trade,"application/json",false).statusCode());
            var created=post(a,"/api/transactions",trade,"application/json",true);
            assertEquals(201,created.statusCode());
            var idMatch=Pattern.compile("\"id\":(\\d+)").matcher(created.body()); assertTrue(idMatch.find());
            String tradeId=idMatch.group(1);
            assertTrue(get(a,"/api/transactions").body().contains("MYR"));
            assertEquals("[]",get(b,"/api/transactions").body());
            assertEquals(404,mutate(b,"/api/transactions/"+tradeId,trade,"application/json",true,"PUT").statusCode());
            assertEquals(404,mutate(b,"/api/transactions/"+tradeId,"","application/json",true,"DELETE").statusCode());
            assertEquals(404,post(b,"/api/transactions/"+tradeId+"/restore","","application/json",true).statusCode());
            assertEquals(403,post(a,"/api/auth/logout","","application/x-www-form-urlencoded",false).statusCode());
            assertEquals(204,post(a,"/api/auth/logout","","application/x-www-form-urlencoded",true).statusCode());
            assertEquals(401,get(a,"/api/auth/me").statusCode());
            assertEquals(200,get(b,"/api/auth/me").statusCode());
        } finally {
            jdbc.update("DELETE FROM portfolio_transaction WHERE user_id IN (SELECT id FROM app_user WHERE email IN (?,?))",emailA,emailB);
            jdbc.update("DELETE FROM app_user WHERE email IN (?,?)",emailA,emailB);
        }
    }
}
