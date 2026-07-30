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

    private final JWSVerifier verifier;
    private final JwtValidationProperties properties;

    public JwtAuthenticationWebFilter(JwtValidationProperties properties) {
        this.properties = properties;
        try {
            this.verifier = new MACVerifier(
                    properties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "Failed to initialise JWT verifier (secret too short?)", e);
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
            // Public paths (login/register/refresh/logout/health) skip validation, but a client
            // could still attach X-User-Id/X-User-Email itself; strip them unconditionally so
            // nothing downstream (including gateway-side rate limiting keyed on X-User-Id) can
            // ever observe a client-forged identity header on a route we didn't authenticate.
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
            if (!signedJWT.verify(verifier)) {
                return unauthorized(exchange, "Invalid token signature");
            }
            claims = signedJWT.getJWTClaimsSet();

            // Validate issuer
            if (!properties.issuer().equals(claims.getIssuer())) {
                return unauthorized(exchange, "Invalid access token");
            }
            // Validate audience
            if (!claims.getAudience().contains(properties.audience())) {
                return unauthorized(exchange, "Invalid access token");
            }
            // Validate expiry
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
        log.debug("Rejected request to {}: {}", exchange.getRequest().getURI().getPath(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
