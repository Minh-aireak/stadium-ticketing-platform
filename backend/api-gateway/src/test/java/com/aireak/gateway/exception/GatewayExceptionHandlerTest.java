package com.aireak.gateway.exception;

import com.aireak.gateway.filter.CorrelationIdWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void mapsConnectExceptionToServiceUnavailable() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings").build());

        handler.handle(exchange, new ConnectException("Connection refused")).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void mapsTimeoutExceptionToGatewayTimeout() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/matches").build());

        handler.handle(exchange, new TimeoutException("Response timeout")).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void preservesResponseStatusExceptionStatus() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/payments").build());

        handler.handle(exchange, new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "too big"))
                .block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void mapsUnknownExceptionToInternalServerError() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/notifications").build());

        handler.handle(exchange, new IllegalStateException("boom")).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void propagatesTheOriginalErrorWhenResponseIsAlreadyCommitted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/matches").build());
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().setComplete().block(Duration.ofSeconds(5));
        IllegalStateException original = new IllegalStateException("too late");

        assertThat(exchange.getResponse().isCommitted()).isTrue();
        assertThatThrownBy(() -> handler.handle(exchange, original).block(Duration.ofSeconds(5)))
                .isSameAs(original);
    }

    // ---- the error body is JSON, and no part of the request may decide its shape ----

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonNode bodyOf(MockServerWebExchange exchange) {
        String raw = exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
        return JSON.readTree(raw);
    }

    /**
     * The correlation id lands in the body straight from the request header. Two things make that
     * exploitable rather than merely untidy: {@code ExceptionHandlingWebHandler} hands this
     * handler the exchange it received itself — the one built BEFORE
     * {@link CorrelationIdWebFilter} makes its sanitised copy for the filter chain — so the header
     * is whatever the client sent; and the body used to be assembled by string concatenation.
     */
    @Test
    void aClientSuppliedCorrelationIdCannotDecideWhatTheErrorBodySays() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/payments/webhook")
                        .header(CorrelationIdWebFilter.HEADER_NAME,
                                "x\",\"detail\":\"Your card was charged successfully")
                        .build());

        handler.handle(exchange, new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE))
                .block(Duration.ofSeconds(5));

        JsonNode body = bodyOf(exchange);
        assertThat(body.get("detail").asString())
                .isNotEqualTo("Your card was charged successfully");
        assertThat(body.get("status").asInt()).isEqualTo(413);
    }

    /**
     * Same hole through the other variable field. {@code URI#getPath()} returns the path DECODED,
     * so {@code %22} arrives here as a raw quote — the exact payload
     * {@code JwtAuthenticationWebFilter} already had to be moved off string concatenation for.
     */
    @Test
    void aQuoteInTheRequestPathCannotDecideWhatTheErrorBodySays() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET,
                        URI.create("/api/v1/matches/x%22,%22detail%22:%22nothing%20is%20wrong")).build());

        handler.handle(exchange, new ConnectException("Connection refused")).block(Duration.ofSeconds(5));

        JsonNode body = bodyOf(exchange);
        assertThat(body.get("detail").asString()).isEqualTo("The downstream service is currently unavailable");
    }

    /** A failure before the filter ran has no id — not the four characters {@code null}. */
    @Test
    void omitsTheCorrelationIdRatherThanPrintingTheWordNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/matches").build());

        handler.handle(exchange, new ConnectException("Connection refused")).block(Duration.ofSeconds(5));

        JsonNode correlationId = bodyOf(exchange).get("correlationId");
        assertThat(correlationId == null || correlationId.isNull()).isTrue();
    }

    /**
     * The id the request was actually processed and logged under is the one
     * {@link CorrelationIdWebFilter} put on the RESPONSE; the request header on this exchange is
     * the unvalidated inbound one. Header and body must not disagree.
     */
    @Test
    void reportsTheCorrelationIdTheRequestWasActuallyProcessedUnder() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/matches")
                        .header(CorrelationIdWebFilter.HEADER_NAME, "not-a-uuid").build());
        String applied = "11111111-2222-4333-8444-555555555555";
        exchange.getResponse().getHeaders().set(CorrelationIdWebFilter.HEADER_NAME, applied);

        handler.handle(exchange, new ConnectException("Connection refused")).block(Duration.ofSeconds(5));

        assertThat(bodyOf(exchange).get("correlationId").asString()).isEqualTo(applied);
    }

    /** A 413 is a known, actionable client error, not an unexplained internal one. */
    @Test
    void describesAnOversizedRequestAsWhatItIs() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/payments/webhook").build());

        handler.handle(exchange, new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE))
                .block(Duration.ofSeconds(5));

        JsonNode body = bodyOf(exchange);
        assertThat(body.get("type").asString()).endsWith("request-too-large");
        assertThat(body.get("detail").asString()).isNotEqualTo("An unexpected error occurred");
    }

    /**
     * RFC 7807 puts extension members at the top level, and so does every backend service —
     * Spring's message converter applies {@code ProblemDetailJacksonMixin}. A gateway filter
     * writing its own body has to apply it too, or {@code timestamp} and {@code correlationId}
     * arrive nested inside a {@code "properties"} object that exists nowhere else on the platform.
     */
    @Test
    void putsExtensionMembersAtTheTopLevelLikeEveryOtherServiceDoes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/matches").build());
        exchange.getResponse().getHeaders().set(CorrelationIdWebFilter.HEADER_NAME,
                "11111111-2222-4333-8444-555555555555");

        handler.handle(exchange, new ConnectException("Connection refused")).block(Duration.ofSeconds(5));

        JsonNode body = bodyOf(exchange);
        assertThat(body.get("properties")).isNull();
        assertThat(body.get("timestamp")).isNotNull();
    }
}
