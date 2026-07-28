package com.aireak.booking.adapter.out.client;

import com.aireak.common.security.JwtAuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints a short-lived JWT for outbound calls to ticket-inventory-service/payment-service that
 * have no authenticated end-user request to forward a token from — {@code confirmReservation}
 * is only ever triggered from {@code PaymentResultConsumer} (Kafka listener thread) or
 * {@code InventoryConfirmationReconciler} (scheduled job thread), and {@code releaseSeats} is
 * sometimes triggered the same way (payment-failure compensation). Signed with the same shared
 * {@code jwt.secret}/{@code issuer}/{@code audience} those services already validate against
 * (see {@link JwtAuthProperties}), so they see a genuinely authenticated caller rather than
 * needing an unauthenticated carve-out for these paths.
 */
@Component
public class InternalServiceTokenProvider {

    private static final String SERVICE_SUBJECT = "booking-service";
    private static final long TTL_SECONDS = 60;

    private final SecretKey secretKey;
    private final JwtAuthProperties properties;

    public InternalServiceTokenProvider(JwtAuthProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String mintServiceToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(SERVICE_SUBJECT)
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TTL_SECONDS)))
                .signWith(secretKey)
                .compact();
    }
}
