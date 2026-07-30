package com.aireak.identity.adapter.out.security;

import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests for {@link JwtTokenGeneratorAdapter}.
 * Uses Nimbus to parse/verify the token instead of the now-removed JJWT dependency.
 */
class JwtTokenGeneratorAdapterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";

    private final JwtProperties properties = new JwtProperties(SECRET, 900, "identity-service", "stadium-clients");
    private final JwtTokenGeneratorAdapter adapter = new JwtTokenGeneratorAdapter(properties);

    @Test
    void generatedTokenCarriesTheAccountsRoleClaim() throws ParseException, JOSEException {
        Account admin = Account.registerAdmin(new Email("admin@example.com"), new HashedPassword("$2a$12$hash"));

        String token = adapter.generateToken(admin);

        JWTClaimsSet claims = parse(token);
        assertThat(claims.getStringClaim("role")).isEqualTo("ADMIN");
        assertThat(claims.getStringClaim("email")).isEqualTo("admin@example.com");
    }

    @Test
    void ordinaryUserAccountGetsUserRoleClaim() throws ParseException, JOSEException {
        Account user = Account.register(new Email("user@example.com"), new HashedPassword("$2a$12$hash"), "token");

        String token = adapter.generateToken(user);

        assertThat(parse(token).getStringClaim("role")).isEqualTo("USER");
    }

    private JWTClaimsSet parse(String token) throws ParseException, JOSEException {
        SignedJWT signedJWT = SignedJWT.parse(token);
        JWSVerifier verifier = new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThat(signedJWT.verify(verifier)).isTrue();
        return signedJWT.getJWTClaimsSet();
    }
}
