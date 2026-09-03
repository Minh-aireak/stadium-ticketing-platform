package com.aireak.common.web.filter;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.security.JwtAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

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
 *
 * <p>Uses Nimbus JOSE+JWT (com.nimbusds:nimbus-jose-jwt) instead of jjwt, because jjwt 0.12.x
 * depends on com.fasterxml.jackson (Jackson 2) which conflicts with Spring Boot 4.1's Jackson 3
 * auto-configuration. Nimbus is already a first-class Spring Security dependency — it powers
 * spring-security-oauth2-jose internally.
 */
@Component
@Order(2)
@EnableConfigurationProperties(JwtAuthProperties.class)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String TYPE_BASE = "https://aireak.com/errors/";
    private static final java.util.Set<String> HTTP_METHODS = java.util.Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final JWSVerifier primaryVerifier;
    private final JWSVerifier previousVerifier;
    private final JWSVerifier internalVerifier;
    private final JwtAuthProperties properties;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules(
            JwtAuthenticationFilter.class.getClassLoader()).build();

    public JwtAuthenticationFilter(JwtAuthProperties properties) {
        this.properties = properties;
        validateSecret("jwt.secret", properties.secret(), true);
        validateSecret("jwt.previous-secret", properties.previousSecret(), false);
        validateSecret("jwt.internal-secret", properties.internalSecret(), false);
        try {
            this.primaryVerifier = new MACVerifier(properties.secret().getBytes(StandardCharsets.UTF_8));
            this.previousVerifier = (properties.previousSecret() != null && !properties.previousSecret().isBlank())
                    ? new MACVerifier(properties.previousSecret().getBytes(StandardCharsets.UTF_8))
                    : null;
            this.internalVerifier = (properties.internalSecret() != null && !properties.internalSecret().isBlank())
                    ? new MACVerifier(properties.internalSecret().getBytes(StandardCharsets.UTF_8))
                    : null;
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to initialise JWT verifier (secret too short?)", e);
        }
    }

    private static void validateSecret(String propertyName, String secret, boolean required) {
        if (secret == null || secret.isBlank()) {
            if (required) {
                throw new IllegalStateException("Required property '" + propertyName + "' is not set or blank");
            }
            return;
        }
        if (secret.startsWith("${") && secret.endsWith("}")) {
            throw new IllegalStateException(
                    "Property '" + propertyName + "' resolved to unexpanded placeholder '" + secret +
                    "'. Please set the corresponding environment variable or provide an explicit test value.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "Property '" + propertyName + "' must be at least 256 bits (32 bytes) for HMAC-SHA256, but was " +
                    secret.getBytes(StandardCharsets.UTF_8).length + " bytes.");
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return properties.excludedPaths().stream().anyMatch(pattern -> matches(pattern, method, path));
    }

    private boolean matches(String pattern, String method, String path) {
        int colon = pattern.indexOf(':');
        if (colon > 0 && HTTP_METHODS.contains(pattern.substring(0, colon).toUpperCase(java.util.Locale.ROOT))) {
            String patternMethod = pattern.substring(0, colon);
            String patternPath = pattern.substring(colon + 1);
            return patternMethod.equalsIgnoreCase(method) && PATH_MATCHER.match(patternPath, path);
        }
        return PATH_MATCHER.match(pattern, path);
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

        JWTClaimsSet claims;
        // Which key actually vouched for this token, not just that some key did — the
        // internal-service check below turns on the difference.
        boolean signedByInternalSecret = false;
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            boolean verified = signedJWT.verify(primaryVerifier);
            if (!verified && previousVerifier != null) {
                verified = signedJWT.verify(previousVerifier);
            }
            if (!verified && internalVerifier != null) {
                verified = signedJWT.verify(internalVerifier);
                signedByInternalSecret = verified;
            }

            if (!verified) {
                reject(response, "Invalid token signature");
                return;
            }
            claims = signedJWT.getJWTClaimsSet();

            // Validate issuer
            if (!properties.issuer().equals(claims.getIssuer())) {
                reject(response, "Invalid access token");
                return;
            }
            // Validate audience
            if (!claims.getAudience().contains(properties.audience())) {
                reject(response, "Invalid access token");
                return;
            }
            // Validate expiry
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                reject(response, "Access token expired");
                return;
            }
        } catch (ParseException ex) {
            reject(response, "Invalid access token");
            return;
        } catch (JOSEException ex) {
            reject(response, "Invalid token signature");
            return;
        }

        Object email = claims.getClaim("email");
        Object role = claims.getClaim("role");
        Object tokenType = claims.getClaim("tokenType");
        String tokenTypeValue = tokenType != null ? String.valueOf(tokenType) : null;

        // The tokenType claim is what AuthenticatedUser#isInternalService answers on, and that
        // answer opens POST /inventory/{id}/confirm (sell seats with no payment) and lets
        // DELETE /inventory/{id}/reserve/{bookingId} skip its ownership check. Verification above
        // is satisfied by ANY of the three keys, so without this the claim was honoured on a token
        // signed with the user-facing jwt.secret — which is the one 8 services and the gateway all
        // hold. InternalServiceTokenProvider's promise that "a leak of one secret can never be used
        // to forge the other kind of token" only holds if the claim is tied to the key that made it.
        //
        // Skipped when no internal secret is configured: InternalServiceTokenProvider then signs
        // with jwt.secret by documented design (local/dev), so there is no second key to demand.
        if (internalVerifier != null && !signedByInternalSecret
                && AuthenticatedUser.TOKEN_TYPE_INTERNAL_SERVICE.equals(tokenTypeValue)) {
            log.warn("Rejected a token claiming tokenType={} that was not signed with jwt.internal-secret (sub={})",
                    AuthenticatedUser.TOKEN_TYPE_INTERNAL_SERVICE, claims.getSubject());
            reject(response, "Invalid access token");
            return;
        }

        AuthenticatedUserContext.set(new AuthenticatedUser(
                claims.getSubject(), email != null ? String.valueOf(email) : null,
                role != null ? String.valueOf(role) : null, token,
                tokenTypeValue));
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
        jsonMapper.writeValue(response.getWriter(), problem);
    }
}
