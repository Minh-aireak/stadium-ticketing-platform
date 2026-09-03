package com.aireak.inventory.adapter.out.client;

import com.aireak.inventory.domain.exception.ShowtimeBookingClosedException;
import com.aireak.inventory.domain.exception.ShowtimeCatalogUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the two local caches {@link ShowtimeCatalogRestAdapter} keeps in front of the REST
 * call to catalog-service — proven here via {@link MockRestServiceServer}, which throws if a
 * request is made without a matching expectation, so any test below that registers no expectation
 * fails loudly if the cache doesn't actually short-circuit the network call.
 */
class ShowtimeCatalogRestAdapterTest {

    private static final String BASE_URL = "http://catalog-service";

    private MockRestServiceServer mockServer;
    private ShowtimeCatalogRestAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new ShowtimeCatalogRestAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "baseUrl", BASE_URL);
    }

    /**
     * The regression. A catalog-service that cannot answer is not a showtime whose booking window
     * has closed, but both used to leave here as ShowtimeBookingClosedException — a DomainException,
     * so GlobalExceptionHandler answered the customer 422. Three things went wrong at once: the
     * status said "your request is the problem" about an outage; the customer was told booking had
     * closed for a match that was still on sale; and because errors.ts only attaches the
     * correlation id on 5xx, the one failure that genuinely needs a Kibana lookup was the one that
     * shipped without an id.
     */
    @Test
    void requireBookable_whenCatalogIsUnreachable_reportsAnOutageNotAClosedWindow() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/showtime-1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                .isInstanceOf(ShowtimeCatalogUnavailableException.class);

        mockServer.verify();
    }

    /** Catalog shedding load (its CatalogOverloadExceptionHandler) is the same story. */
    @Test
    void requireBookable_whenCatalogShedsLoad_reportsAnOutage() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/showtime-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                .isInstanceOf(ShowtimeCatalogUnavailableException.class);

        mockServer.verify();
    }

    /**
     * The other side of the split, and the reason it is a split rather than a blanket change: a
     * 404 IS catalog answering definitively — there is no such showtime — so it stays the closed
     * window it always was. Fail closed either way; only the story told about it differs.
     */
    @Test
    void requireBookable_whenCatalogDoesNotKnowTheShowtime_staysAClosedWindow() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/showtime-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                .isInstanceOf(ShowtimeBookingClosedException.class);

        mockServer.verify();
    }

    /** A showtime catalog says is not bookable is unchanged: a definite no, and still 422. */
    @Test
    void requireBookable_whenCatalogSaysNotBookable_staysAClosedWindow() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/showtime-1"))
                .andRespond(withSuccess(
                        "{\"showtimeId\":\"showtime-1\",\"startTime\":\"2099-01-01T18:00:00Z\",\"bookable\":false}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                .isInstanceOf(ShowtimeBookingClosedException.class)
                .isNotInstanceOf(ShowtimeCatalogUnavailableException.class);

        mockServer.verify();
    }

    @Test
    void requireBookable_whenCatalogReportsBookable_returnsNormally() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/showtime-1"))
                .andRespond(withSuccess(
                        "{\"showtimeId\":\"showtime-1\",\"startTime\":\"2099-01-01T18:00:00Z\",\"bookable\":true}",
                        MediaType.APPLICATION_JSON));

        adapter.requireBookable("showtime-1");

        mockServer.verify();
    }

    @Test
    void requireBookable_forAShowtimeMarkedUnbookableByEvent_rejectsWithoutCallingCatalog() {
        adapter.markUnbookable(List.of("showtime-1"));

        assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                .isInstanceOf(ShowtimeBookingClosedException.class);
    }

    @Test
    void requireBookable_forAShowtimeWhoseCachedStartTimeHasPassed_rejectsWithoutCallingCatalog() {
        adapter.rememberStartTime("showtime-1", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> adapter.requireBookable("showtime-1"))
                .isInstanceOf(ShowtimeBookingClosedException.class);
    }

    /**
     * The cached start time only ever short-circuits the REJECT path — an ACCEPT ("still in the
     * future") always falls through to the authoritative REST call, since bookability can also be
     * revoked by a cancellation this adapter doesn't yet know about.
     */
    @Test
    void requireBookable_forAShowtimeWhoseCachedStartTimeIsStillInTheFuture_stillCallsCatalog() {
        adapter.rememberStartTime("showtime-1", Instant.now().plusSeconds(3600));
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/showtime-1"))
                .andRespond(withSuccess(
                        "{\"showtimeId\":\"showtime-1\",\"startTime\":\"2099-01-01T18:00:00Z\",\"bookable\":true}",
                        MediaType.APPLICATION_JSON));

        adapter.requireBookable("showtime-1");

        mockServer.verify();
    }

    @Test
    void requireBookable_forAnUnrelatedShowtime_isNotAffectedByAnotherShowtimesCachedState() {
        adapter.markUnbookable(List.of("cancelled-showtime"));
        mockServer.expect(requestTo(BASE_URL + "/api/v1/showtimes/other-showtime"))
                .andRespond(withSuccess(
                        "{\"showtimeId\":\"other-showtime\",\"startTime\":\"2099-01-01T18:00:00Z\",\"bookable\":true}",
                        MediaType.APPLICATION_JSON));

        adapter.requireBookable("other-showtime");

        mockServer.verify();
    }
}
