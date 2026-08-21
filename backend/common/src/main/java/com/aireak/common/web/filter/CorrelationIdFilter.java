package com.aireak.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter that extracts (or generates) a correlation ID from the
 * incoming {@code X-Correlation-Id} header and stores it in the SLF4J MDC
 * so all log lines within the request carry the same trace context.
 *
 * <p>Also propagates the correlation ID in the response header.
 *
 * <p>An inbound ID is only trusted when it is a well-formed UUID v4; anything else is replaced
 * with a generated one. Every service port is published by docker-compose, so this filter is
 * reachable without going through the gateway — and whatever arrives here ends up verbatim in a
 * top-level field of the ECS log file. The rule mirrors the gateway's {@code
 * CorrelationIdWebFilter}; it is duplicated rather than shared because api-gateway is the one
 * module that does not depend on {@code common}.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY               = "correlationId";

    // Checked before the regex, so an arbitrarily large header value is rejected on length alone.
    private static final int MAX_INBOUND_LENGTH = 64;

    private static final Pattern UUID_V4_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String inbound = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId = isValid(inbound) ? inbound : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean isValid(String candidate) {
        return candidate != null
                && candidate.length() <= MAX_INBOUND_LENGTH
                && UUID_V4_PATTERN.matcher(candidate).matches();
    }
}
