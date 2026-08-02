package com.aireak.inventory.adapter.out.client;

import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import com.aireak.inventory.domain.exception.ShowtimeBookingClosedException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ShowtimeCatalogRestAdapter implements ShowtimeCatalogPort {

    @Qualifier("catalogRestClient")
    private final RestClient restClient;

    @Value("${services.catalog.base-url:http://localhost:8082}")
    private String baseUrl;

    @Override
    public void requireBookable(String showtimeId) {
        try {
            ShowtimeAvailability response = restClient.get()
                    .uri(baseUrl + "/api/v1/showtimes/{showtimeId}", showtimeId)
                    .retrieve()
                    .body(ShowtimeAvailability.class);
            if (response == null || !response.bookable()
                    || response.startTime() == null || !response.startTime().isAfter(Instant.now())) {
                throw new ShowtimeBookingClosedException(showtimeId);
            }
        } catch (ShowtimeBookingClosedException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ShowtimeBookingClosedException(showtimeId, exception);
        }
    }

    record ShowtimeAvailability(String showtimeId, Instant startTime, boolean bookable) {
    }
}
