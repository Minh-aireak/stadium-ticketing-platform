package com.aireak.common.web.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private static final String VALID_ID = "3f2a1b9c-4d5e-4f6a-8b7c-9d0e1f2a3b4c";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsAWellFormedInboundId() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();

        MockHttpServletResponse response = doFilterWithHeader(VALID_ID, duringRequest);

        assertThat(duringRequest.get()).isEqualTo(VALID_ID);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(VALID_ID);
    }

    @Test
    void generatesAnIdWhenTheHeaderIsAbsent() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();

        MockHttpServletResponse response = doFilterWithHeader(null, duringRequest);

        assertThat(duringRequest.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(duringRequest.get());
    }

    /**
     * Every service port is published by docker-compose, so this filter is reachable without the
     * gateway in front of it — and the value lands verbatim in a top-level ECS log field.
     */
    @Test
    void replacesAMalformedInboundId() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();

        doFilterWithHeader("not-a-uuid\nlevel=ERROR", duringRequest);

        assertThat(duringRequest.get()).doesNotContain("not-a-uuid");
    }

    @Test
    void replacesAnOverlongInboundId() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();

        doFilterWithHeader(VALID_ID.repeat(4), duringRequest);

        assertThat(duringRequest.get()).hasSize(VALID_ID.length());
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        doFilterWithHeader(VALID_ID, new AtomicReference<>());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private MockHttpServletResponse doFilterWithHeader(String inbound, AtomicReference<String> duringRequest)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings");
        if (inbound != null) {
            request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, inbound);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> duringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);
        return response;
    }
}
