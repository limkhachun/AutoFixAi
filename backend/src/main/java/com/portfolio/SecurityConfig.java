package com.portfolio;

import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return email -> {
            var users = jdbc.query("SELECT email,password_hash FROM app_user WHERE email=?",
                (rs, row) -> User.withUsername(rs.getString("email"))
                    .password(rs.getString("password_hash")).roles("USER").build(),
                email.trim().toLowerCase(Locale.ROOT));
            if (users.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
            return users.getFirst();
        };
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
            .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/register", "/api/auth/login", "/error").permitAll()
            .anyRequest().authenticated());
        // Keep session-based CSRF protection enabled, including registration/login/logout.
        http.formLogin(form -> form.loginProcessingUrl("/api/auth/login").usernameParameter("email")
            .successHandler((request, response, authentication) -> response.setStatus(204))
            .failureHandler((request, response, exception) -> {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Invalid email or password\"}");
            }));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204))
            .deleteCookies("JSESSIONID"));
        http.requestCache(cache -> cache.disable());
        http.exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
            .accessDeniedHandler((request, response, exception) -> response.setStatus(403)));
        return http.build();
    }
}
