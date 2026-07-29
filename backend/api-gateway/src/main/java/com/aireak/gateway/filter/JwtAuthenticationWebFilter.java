package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Validates the access token JWT locally (signature, issuer, audience, expiration, algorithm) —
 * the gateway never calls identity-service to check a token. Trades a network hop for a
 * self-contained, cryptographically verifiable claim, which is the point of using JWTs here.
 *
 * <p>{@link Jwts#parser()} bound to an HMAC {@link SecretKey} only ever accepts HMAC signatures
 * of a matching strength and never accepts an unsigned ("none") or otherwise-algorithm token —
 * JJWT does not have an implicit trust path for {@code alg=none}.
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

    private final SecretKey secretKey;
    private final JwtValidationProperties properties;

    public JwtAuthenticationWebFilter(JwtValidationProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
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

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.issuer())
                    .requireAudience(properties.audience())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            return unauthorized(exchange, "Access token expired");
        } catch (SignatureException ex) {
            return unauthorized(exchange, "Invalid token signature");
        } catch (JwtException | IllegalArgumentException ex) {
            return unauthorized(exchange, "Invalid access token");
        }

        Object email = claims.get("email");
        Object role = claims.get("role");
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
