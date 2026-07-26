package com.aireak.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    @Test
    void keepsAValidUuidV4() {
        String validV4 = UUID.randomUUID().toString();
        MockServerWebExchange exchange = exchangeWithHeader(validV4);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME))
                .isEqualTo(validV4);
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME))
                .isEqualTo(validV4);
    }

    @Test
    void generatesANewIdWhenHeaderMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/anything").build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        String generated = exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME);
        assertThat(generated).isNotNull();
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
    }

    @Test
    void generatesANewIdWhenHeaderIsNotUuidV4() {
        MockServerWebExchange exchange = exchangeWithHeader("not-a-valid-correlation-id");

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        String generated = exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME);
        assertThat(generated).isNotEqualTo("not-a-valid-correlation-id");
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void generatesANewIdWhenHeaderExceeds64Characters() {
        String tooLong = "a".repeat(65);
        MockServerWebExchange exchange = exchangeWithHeader(tooLong);

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        String generated = exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME);
        assertThat(generated).isNotEqualTo(tooLong);
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void neverDuplicatesTheResponseHeader() {
        MockServerWebExchange exchange = exchangeWithHeader(UUID.randomUUID().toString());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdWebFilter.HEADER_NAME)).hasSize(1);
    }

    private static MockServerWebExchange exchangeWithHeader(String value) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/anything").header(CorrelationIdWebFilter.HEADER_NAME, value).build());
    }
}
