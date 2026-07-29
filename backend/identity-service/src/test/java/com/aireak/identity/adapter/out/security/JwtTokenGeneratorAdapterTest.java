package com.aireak.identity.adapter.out.security;

import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenGeneratorAdapterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";

    private final JwtProperties properties = new JwtProperties(SECRET, 900, "identity-service", "stadium-clients");
    private final JwtTokenGeneratorAdapter adapter = new JwtTokenGeneratorAdapter(properties);

    @Test
    void generatedTokenCarriesTheAccountsRoleClaim() {
        Account admin = Account.registerAdmin(new Email("admin@example.com"), new HashedPassword("$2a$12$hash"));

        String token = adapter.generateToken(admin);

        Claims claims = parse(token);
        assertThat(claims.get("role")).isEqualTo("ADMIN");
        assertThat(claims.get("email")).isEqualTo("admin@example.com");
    }

    @Test
    void ordinaryUserAccountGetsUserRoleClaim() {
        Account user = Account.register(new Email("user@example.com"), new HashedPassword("$2a$12$hash"), "token");

        String token = adapter.generateToken(user);

        assertThat(parse(token).get("role")).isEqualTo("USER");
    }

    private Claims parse(String token) {
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }
}
