package com.aireak.common.web.client;

import com.aireak.common.web.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestInitializer;

/**
 * Copies the current request's correlation ID onto every outgoing HTTP call, so a service-to-service
 * hop keeps the caller's ID instead of the callee inventing a new one.
 *
 * <p>{@link CorrelationIdFilter} generates an ID when the {@code X-Correlation-Id} header is absent.
 * That is the right behaviour for a request arriving from outside, but it means an internal call
 * that forgets the header starts a fresh trail: a booking saga would log under one ID in
 * booking-service and under different ones in ticket-inventory-service and payment-service, which
 * is precisely the correlation the saga most needs.
 *
 * <p>Registered once per {@code RestClient} via {@code builder.requestInitializer(...)} rather than
 * at each call site, so a newly added downstream call cannot forget it.
 */
public class CorrelationIdRequestInitializer implements ClientHttpRequestInitializer {

    @Override
    public void initialize(ClientHttpRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        // Absent on calls made off a @Scheduled thread (reconcilers), which never had a request to
        // inherit from. Send nothing rather than a placeholder: the callee then mints its own ID,
        // which is the honest representation of a chain that genuinely started there.
        if (correlationId != null && !correlationId.isBlank()) {
            request.getHeaders().set(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
    }
}
