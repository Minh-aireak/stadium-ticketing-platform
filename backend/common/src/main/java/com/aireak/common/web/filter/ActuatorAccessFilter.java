package com.aireak.common.web.filter;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
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
import java.time.Instant;
import java.util.Optional;

/**
 * Secures Spring Boot Actuator endpoints across backend microservices.
 *
 * <p>Public access is allowed only to {@code /actuator/health} and {@code /actuator/info}.
 * Sensitive operational endpoints (metrics, env, prometheus, beans, circuitbreakers, etc.)
 * require authentication and the {@code ADMIN} role.
 */
@Component
@Order(3)
public class ActuatorAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActuatorAccessFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String REQUIRED_ROLE = "ADMIN";
    private static final String TYPE_BASE = "https://aireak.com/errors/";

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules(
            ActuatorAccessFilter.class.getClassLoader()).build();

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isPublicActuatorPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AuthenticatedUser> userOpt = AuthenticatedUserContext.get();
        if (userOpt.isEmpty()) {
            reject(response, HttpStatus.UNAUTHORIZED, "Authentication required for sensitive actuator endpoints", "unauthorized");
            return;
        }

        AuthenticatedUser user = userOpt.get();
        if (!REQUIRED_ROLE.equals(user.role())) {
            log.warn("Access denied to actuator endpoint {} for user {} with role {}", path, user.userId(), user.role());
            reject(response, HttpStatus.FORBIDDEN, "ADMIN role required for sensitive actuator endpoints", "forbidden");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicActuatorPath(String path) {
        return path.equals("/actuator") || path.equals("/actuator/")
                || PATH_MATCHER.match("/actuator/health/**", path)
                || PATH_MATCHER.match("/actuator/info/**", path);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String detail, String errorType) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + errorType));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("timestamp", Instant.now());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getWriter(), problem);
    }
}
