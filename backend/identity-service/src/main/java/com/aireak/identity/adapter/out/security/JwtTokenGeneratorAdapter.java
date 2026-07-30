package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.model.Account;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Outbound adapter: generates signed JWT access tokens using Nimbus JOSE+JWT.
 * Implements {@link TokenGeneratorPort}.
 *
 * <p>Replaces the previous JJWT 0.12.x implementation. jjwt-jackson depended on
 * com.fasterxml.jackson (Jackson 2) which conflicts with Spring Boot 4.1's Jackson 3
 * auto-configuration. Nimbus JOSE+JWT is Spring Security's own JWT library
 * (used internally by spring-security-oauth2-jose) and has no Jackson dependency.
 *
 * <p>Claims: sub=accountId, email, status, role, iss, aud, jti. Signed with HS256 —
 * the key must be at least 256 bits (32 bytes) long.
 */
@Component
public class JwtTokenGeneratorAdapter implements TokenGeneratorPort {

    private final JWSSigner signer;
    private final JwtProperties properties;

    public JwtTokenGeneratorAdapter(JwtProperties properties) {
        this.properties = properties;
        try {
            this.signer = new MACSigner(
                    properties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "Failed to initialise JWT signer (secret too short — must be >= 32 bytes for HS256)", e);
        }
    }

    @Override
    public String generateToken(Account account) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.expirationSeconds());

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .subject(account.getId().toString())
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .claim("email", account.getEmail().value())
                .claim("status", account.getStatus().name())
                .claim("role", account.getRole().name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                claimsSet);
        try {
            signedJWT.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT token", e);
        }
        return signedJWT.serialize();
    }
}
