package com.aireak.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorAccessWebFilterTest {

    private final ActuatorAccessWebFilter filter = new ActuatorAccessWebFilter();

    @Test
    void rejectsMetricsWithoutAdminRole() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/metrics").build());
        exchange.getAttributes().put(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE, "USER");

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(chainInvoked[0]).isFalse();
    }

    @Test
    void rejectsMetricsWhenRoleAttributeIsAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/metrics/jvm.memory.used").build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsGatewayEndpointWithoutAdminRole() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/gateway/routes").build());
        exchange.getAttributes().put(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE, "USER");

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allowsMetricsForAdminRole() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/metrics").build());
        exchange.getAttributes().put(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE, "ADMIN");

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
    }

    @Test
    void allowsGatewayEndpointForAdminRole() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/gateway/routes").build());
        exchange.getAttributes().put(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE, "ADMIN");

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
    }

    @Test
    void leavesUnrelatedPathsUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
    }

    @Test
    void allowsPrometheusWithoutAdminRole() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/prometheus").build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
    }

    /**
     * The 403 body carried the request path, and {@code URI#getPath()} returns it DECODED — so a
     * {@code %22} in the path arrived here as a raw quote and, while the body was built by string
     * concatenation, let the caller add fields to it. The same hole
     * {@code JwtAuthenticationWebFilter} was already moved off concatenation for; this filter and
     * {@code GatewayExceptionHandler} were the two left behind.
     */
    @Test
    void aQuoteInTheRequestPathCannotDecideWhatTheForbiddenBodySays() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET,
                        URI.create("/actuator/metrics/x%22,%22detail%22:%22access%20granted")).build());
        exchange.getAttributes().put(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE, "USER");

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        String raw = exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
        JsonNode body = JsonMapper.builder().build().readTree(raw);
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("detail").asString()).isNotEqualTo("access granted");
    }
}
