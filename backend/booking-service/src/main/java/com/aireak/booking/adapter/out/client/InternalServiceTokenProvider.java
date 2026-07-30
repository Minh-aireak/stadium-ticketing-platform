package com.aireak.booking.adapter.out.client;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.JwtAuthProperties;
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
 * Mints a short-lived JWT for outbound calls to ticket-inventory-service/payment-service that
 * have no authenticated end-user request to forward a token from — {@code confirmReservation}
 * is only ever triggered from {@code PaymentResultConsumer} (Kafka listener thread) or
 * {@code InventoryConfirmationReconciler} (scheduled job thread), and {@code releaseSeats} is
 * sometimes triggered the same way (payment-failure compensation). Signed with
 * {@code jwt.internal-secret} — deliberately distinct from the user-facing {@code jwt.secret}
 * (see {@link JwtAuthProperties}) so a leak of one secret can never be used to forge the other
 * kind of token — falling back to {@code jwt.secret} only when no internal secret is configured
 * (e.g. local/dev), so this keeps working before every environment sets {@code JWT_INTERNAL_SECRET}.
 *
 * <p>Migrated from JJWT 0.12.x to Nimbus JOSE+JWT. jjwt-jackson depended on
 * com.fasterxml.jackson (Jackson 2) which conflicts with Spring Boot 4.1's Jackson 3
 * auto-configuration. Nimbus has no Jackson dependency.
 */
@Component
public class InternalServiceTokenProvider {

    private static final String SERVICE_SUBJECT = "booking-service";
    private static final long TTL_SECONDS = 60;

    private final JWSSigner signer;
    private final JwtAuthProperties properties;

    public InternalServiceTokenProvider(JwtAuthProperties properties) {
        this.properties = properties;
        String signingSecret = (properties.internalSecret() != null && !properties.internalSecret().isBlank())
                ? properties.internalSecret()
                : properties.secret();
        try {
            this.signer = new MACSigner(signingSecret.getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "Failed to initialise JWT signer in InternalServiceTokenProvider (secret too short?)", e);
        }
    }

    public String mintServiceToken() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .subject(SERVICE_SUBJECT)
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(TTL_SECONDS)))
                .claim("tokenType", AuthenticatedUser.TOKEN_TYPE_INTERNAL_SERVICE)
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            signedJWT.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign internal service JWT", e);
        }
        return signedJWT.serialize();
    }
}
