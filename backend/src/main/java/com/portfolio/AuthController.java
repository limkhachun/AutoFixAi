package com.portfolio;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final SimulationService simulation;
    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, SimulationService simulation) {
        this.jdbc = jdbc; this.passwords = passwords; this.simulation=simulation;
    }
    record Registration(@NotBlank @Email @Size(max=254) String email,
                        @NotBlank @Size(min=12,max=64) String password,
                        @NotBlank @Pattern(regexp="en|zh") String language) {}
    record Account(long id, String email, String language) {}

    @GetMapping("/csrf")
    Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,String> register(@Valid @RequestBody Registration input) {
        if(simulation.isAdmin(input.email().trim()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin email is reserved. Provision the account before configuring admin access.");
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        try {
            jdbc.update("INSERT INTO app_user (email,password_hash,preferred_language) VALUES (?,?,?)",
                input.email().trim().toLowerCase(Locale.ROOT), passwords.encode(input.password()), input.language());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account already exists");
        }
        return Map.of("message", "Account created. Sign in to continue.");
    }

    @GetMapping("/me")
    Account me(Principal principal) {
        return jdbc.queryForObject("SELECT id,email,preferred_language FROM app_user WHERE email=?",
            (rs,row) -> new Account(rs.getLong("id"),rs.getString("email"),rs.getString("preferred_language")),
            principal.getName());
    }
}
