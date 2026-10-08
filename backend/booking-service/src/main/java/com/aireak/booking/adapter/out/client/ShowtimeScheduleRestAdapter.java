package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.booking.application.port.out.ShowtimeSchedulePort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

/**
 * Outbound REST adapter: reads a showtime's kickoff from match-catalog-service's public
 * {@code GET /api/v1/showtimes/{showtimeId}} — the same endpoint ticket-inventory-service checks
 * bookability against, so no token is sent.
 *
 * <p>No retry and a short timeout: this runs inside a customer's cancel request, and an answer that
 * does not come quickly is better reported as 503 with a Retry-After than waited on. It fails closed
 * — with no kickoff there is no deadline to check, and a paid cancel past its deadline would refund
 * money the platform no longer owes.
 */
@Slf4j
@Component
public class ShowtimeScheduleRestAdapter implements ShowtimeSchedulePort {

    private final RestClient restClient;
    private final String baseUrl;

    public ShowtimeScheduleRestAdapter(@Qualifier("catalogRestClient") RestClient restClient,
                                       @Value("${services.match-catalog.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public Instant kickoffOf(String showtimeId) {
        ShowtimeView showtime;
        try {
            showtime = restClient.get()
                    .uri(baseUrl + "/api/v1/showtimes/{showtimeId}", showtimeId)
                    .retrieve()
                    .body(ShowtimeView.class);
        } catch (HttpClientErrorException.NotFound e) {
            // The booking names a showtime the catalog has never heard of: not an outage, and not
            // something a retry changes.
            throw new IllegalStateException("match-catalog-service does not know showtime " + showtimeId, e);
        } catch (RestClientException e) {
            log.error("Could not read the kickoff time from match-catalog-service: showtimeId={}, error={}",
                    showtimeId, e.getMessage());
            throw new OutboundServiceUnavailableException("Match catalog service unavailable", e);
        }
        if (showtime == null || showtime.startTime() == null) {
            throw new IllegalStateException("match-catalog-service returned no start time for showtime " + showtimeId);
        }
        log.debug("Kickoff read from match-catalog-service: showtimeId={}, kickoff={}", showtimeId, showtime.startTime());
        return showtime.startTime();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShowtimeView(String showtimeId, Instant startTime) {}
}
