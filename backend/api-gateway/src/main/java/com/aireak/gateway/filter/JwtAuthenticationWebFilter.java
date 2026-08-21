package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Date;

/**
 * Validates the access token JWT locally (signature, issuer, audience, expiration, algorithm) —
 * the gateway never calls identity-service to check a token. Trades a network hop for a
 * self-contained, cryptographically verifiable claim, which is the point of using JWTs here.
 *
 * <p>Uses Nimbus JOSE+JWT ({@link MACVerifier}) bound to an HMAC secret — only ever accepts
 * HMAC HS256 signatures, never an unsigned ("none") or otherwise-algorithm token.
 *
 * <p>Replaces the previous JJWT 0.12.x implementation. jjwt-jackson depended on
 * com.fasterxml.jackson (Jackson 2) which conflicts with Spring Boot 4.1's Jackson 3
 * auto-configuration. Nimbus is Spring Security's own JWT library (spring-security-oauth2-jose)
 * and has no Jackson dependency.
 *
 * <p>On success, downstream services receive {@code X-User-Id} / {@code X-User-Email} set by
 * the gateway; any such headers on the inbound request are stripped first so a client can't
 * spoof identity by just sending the header itself.
 */
@Component
@Order(-50)
public class JwtAuthenticationWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationWebFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * Exchange attribute (gateway-internal only, never forwarded as a header) carrying the
     * validated token's {@code role} claim — read by {@link ActuatorAccessWebFilter} to gate
     * admin-only actuator endpoints. There is no Spring Security filter chain running in this
     * (WebFlux) service, so {@code management.endpoint.*.roles} alone cannot enforce anything
     * here; this attribute is the actual enforcement path.
     */
    public static final String USER_ROLE_ATTRIBUTE = JwtAuthenticationWebFilter.class.getName() + ".USER_ROLE";

    private static final String TYPE_BASE = "https://aireak.com/errors/";

    private final JWSVerifier primaryVerifier;
    private final JWSVerifier previousVerifier;
    private final JwtValidationProperties properties;

    public JwtAuthenticationWebFilter(JwtValidationProperties properties) {
        this.properties = properties;
        try {
            this.primaryVerifier = new MACVerifier(properties.secret().getBytes(StandardCharsets.UTF_8));
            this.previousVerifier = (properties.previousSecret() != null && !properties.previousSecret().isBlank())
                    ? new MACVerifier(properties.previousSecret().getBytes(StandardCharsets.UTF_8))
                    : null;
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to initialise JWT verifier (secret too short?)", e);
        }
    }

    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isPublic(path)) {
            ServerHttpRequest strippedRequest = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-User-Email");
                    })
                    .build();
            return chain.filter(exchange.mutate().request(strippedRequest).build());
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing bearer token");
        }
        String token = authorization.substring("Bearer ".length()).trim();

        JWTClaimsSet claims;
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            boolean verified = signedJWT.verify(primaryVerifier);
            if (!verified && previousVerifier != null) {
                verified = signedJWT.verify(previousVerifier);
            }
            if (!verified) {
                return unauthorized(exchange, "Invalid token signature");
            }
            claims = signedJWT.getJWTClaimsSet();

            if (!properties.issuer().equals(claims.getIssuer())) {
                return unauthorized(exchange, "Invalid access token");
            }
            if (!claims.getAudience().contains(properties.audience())) {
                return unauthorized(exchange, "Invalid access token");
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                return unauthorized(exchange, "Access token expired");
            }
        } catch (ParseException ex) {
            return unauthorized(exchange, "Invalid access token");
        } catch (JOSEException ex) {
            return unauthorized(exchange, "Invalid token signature");
        }

        Object email = claims.getClaim("email");
        Object role = claims.getClaim("role");
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-User-Id");
                    headers.remove("X-User-Email");
                    headers.set("X-User-Id", claims.getSubject());
                    if (email != null) {
                        headers.set("X-User-Email", String.valueOf(email));
                    }
                })
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        if (role != null) {
            mutatedExchange.getAttributes().put(USER_ROLE_ATTRIBUTE, String.valueOf(role));
        }
        return chain.filter(mutatedExchange);
    }

    private boolean isPublic(String path) {
        return properties.publicPaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        CorrelationIdWebFilter.withCorrelationId(exchange, () ->
                log.debug("Rejected request to {}: {}", exchange.getRequest().getURI().getPath(), reason));
        org.springframework.http.server.reactive.ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);

        String body = String.format(
                "{\"type\":\"%sunauthorized\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\",\"instance\":\"%s\",\"timestamp\":\"%s\"}",
                TYPE_BASE, reason, exchange.getRequest().getURI().getPath(), java.time.Instant.now()
        );
        org.springframework.core.io.buffer.DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
