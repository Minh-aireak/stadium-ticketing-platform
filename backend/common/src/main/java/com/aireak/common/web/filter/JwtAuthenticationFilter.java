package com.aireak.common.web.filter;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.security.JwtAuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Validates every inbound request's {@code Authorization} bearer JWT independently of
 * api-gateway's own {@code JwtAuthenticationWebFilter} — this service must not trust the
 * gateway's edge check alone, since it is directly reachable (bypassing the gateway) inside
 * the docker network, in tests, and by any client that discovers its port.
 *
 * <p>Same HMAC secret/issuer/audience as the gateway and identity-service's token generator
 * (see {@link JwtAuthProperties}). On success, the authenticated identity is exposed via
 * {@link AuthenticatedUserContext} for the controller/application layer to read (e.g. to
 * compare against a customerId in the request body).
 */
@Component
@Order(2)
@EnableConfigurationProperties(JwtAuthProperties.class)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String TYPE_BASE = "https://aireak.com/errors/";

    private final SecretKey secretKey;
    private final JwtAuthProperties properties;
    // Deliberately not injected: this filter must serialize a 401 body even in a slice test (or
    // any context) that doesn't happen to expose the app's own ObjectMapper bean — the fixed,
    // tiny ProblemDetail shape here needs none of that bean's app-wide customization anyway.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public JwtAuthenticationFilter(JwtAuthProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return properties.excludedPaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            reject(response, "Missing bearer token");
            return;
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
            reject(response, "Access token expired");
            return;
        } catch (SignatureException ex) {
            reject(response, "Invalid token signature");
            return;
        } catch (JwtException | IllegalArgumentException ex) {
            reject(response, "Invalid access token");
            return;
        }

        Object email = claims.get("email");
        AuthenticatedUserContext.set(new AuthenticatedUser(
                claims.getSubject(), email != null ? String.valueOf(email) : null, token));
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthenticatedUserContext.clear();
        }
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        log.debug("Rejected request: {}", reason);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, reason);
        problem.setType(URI.create(TYPE_BASE + "unauthorized"));
        problem.setTitle("Unauthorized");
        problem.setProperty("timestamp", Instant.now());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
