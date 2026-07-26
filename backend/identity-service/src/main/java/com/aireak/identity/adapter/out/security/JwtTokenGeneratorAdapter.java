package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.model.Account;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Outbound adapter: generates signed JWT access tokens using JJWT 0.12.x.
 * Implements {@link TokenGeneratorPort}.
 *
 * <p>Claims: sub=accountId, email, status, iss, aud, jti. Signed with HS256 —
 * the key length is validated by {@link Keys#hmacShaKeyFor} (rejects weak/short secrets).
 */
@Component
public class JwtTokenGeneratorAdapter implements TokenGeneratorPort {

    private final SecretKey secretKey;
    private final JwtProperties properties;

    public JwtTokenGeneratorAdapter(JwtProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
    }

    @Override
    public String generateToken(Account account) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.expirationSeconds());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(account.getId().toString())
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .claim("email", account.getEmail().value())
                .claim("status", account.getStatus().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }
}
