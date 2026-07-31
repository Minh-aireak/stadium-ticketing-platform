package com.aireak.common.web.filter;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorAccessFilterTest {

    private final ActuatorAccessFilter filter = new ActuatorAccessFilter();

    @AfterEach
    void clearContext() {
        AuthenticatedUserContext.clear();
    }

    @Test
    void allowsHealthWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, alwaysInvokedChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsInfoWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/info");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, alwaysInvokedChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMetricsWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsMetricsForNonAdminRole() throws Exception {
        AuthenticatedUserContext.set(new AuthenticatedUser("user-1", "user@example.com", "USER", "token"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsMetricsForAdminRole() throws Exception {
        AuthenticatedUserContext.set(new AuthenticatedUser("admin-1", "admin@example.com", "ADMIN", "token"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, alwaysInvokedChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsPrometheusWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, alwaysInvokedChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotFilterNonActuatorPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/matches");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, alwaysInvokedChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private FilterChain alwaysInvokedChain() {
        return (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
    }

    private FilterChain neverInvokedChain() {
        return (req, res) -> {
            throw new AssertionError("Filter chain must not be invoked when access is denied");
        };
    }
}
