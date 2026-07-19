package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.domain.model.Account;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Outbound adapter: generates signed JWT tokens using JJWT 0.12.x.
 * Implements {@link TokenGeneratorPort}.
 *
 * <p>Claims: sub=accountId, email, status. Signed with HS256.
 */
@Slf4j
@Component
public class JwtTokenGeneratorAdapter implements TokenGeneratorPort {

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtTokenGeneratorAdapter(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    @Override
    public String generateToken(Account account) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .subject(account.getId().toString())
                .claim("email", account.getEmail().value())
                .claim("status", account.getStatus().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }
}
