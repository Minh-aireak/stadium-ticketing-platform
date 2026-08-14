package com.aireak.catalog.domain.model;

import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.catalog.domain.exception.InvalidMatchStatusException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("150000");

    private Match matchWithShowtime() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");
        match.addShowtime(new Showtime(Instant.now().plusSeconds(3600), StadiumCatalog.MY_DINH,
                432, BASE_PRICE, "VND"));
        // Drain the ShowtimeAddedEvent this raises so tests using this helper to reach a
        // "has one showtime" fixture can assert on their own event (e.g. MatchPublishedEvent)
        // in isolation, without also having to account for this setup step's event.
        match.pullDomainEvents();
        return match;
    }

    @Test
    void createStartsInDraftWithNoShowtimesAndNoEvents() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");

        assertThat(match.getStatus()).isEqualTo(MatchStatus.DRAFT);
        assertThat(match.getMatchId()).isNotBlank();
        assertThat(match.getShowtimes()).isEmpty();
        assertThat(match.pullDomainEvents()).isEmpty();
    }

    @Test
    void addShowtimeAppendsToListWhenDraft() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");

        match.addShowtime(new Showtime(Instant.now().plusSeconds(3600), StadiumCatalog.MY_DINH,
                432, BASE_PRICE, "VND"));

        assertThat(match.getShowtimes()).hasSize(1);
    }

    @Test
    void addShowtimeRaisesShowtimeAddedEvent() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");
        Showtime showtime = new Showtime(Instant.now().plusSeconds(3600), StadiumCatalog.MY_DINH,
                432, BASE_PRICE, "VND");

        match.addShowtime(showtime);

        List<Object> events = match.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ShowtimeAddedEvent.class);
        ShowtimeAddedEvent event = (ShowtimeAddedEvent) events.get(0);
        assertThat(event.matchId()).isEqualTo(match.getMatchId());
        assertThat(event.showtimeId()).isEqualTo(showtime.getShowtimeId());
        assertThat(event.stadiumId()).isEqualTo(StadiumCatalog.MY_DINH);
        assertThat(event.startTime()).isEqualTo(showtime.getStartTime());
        assertThat(event.totalSeats()).isEqualTo(432);
        assertThat(event.basePrice()).isEqualByComparingTo(BASE_PRICE);
        assertThat(event.currency()).isEqualTo("VND");
    }

    @Test
    void addShowtimeRejectsWhenNotDraft() {
        Match match = matchWithShowtime();
        match.publish();

        assertThatThrownBy(() -> match.addShowtime(new Showtime(Instant.now(), "venue-2", 50, BASE_PRICE, "VND")))
                .isInstanceOf(InvalidMatchStatusException.class);
    }

    @Test
    void publishRejectsWhenNoShowtimes() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");

        assertThatThrownBy(match::publish)
                .isInstanceOf(InvalidMatchStatusException.class);
    }

    @Test
    void publishTransitionsToPublishedAndRaisesEvent() {
        Match match = matchWithShowtime();

        match.publish();

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PUBLISHED);
        List<Object> events = match.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(MatchPublishedEvent.class);
        assertThat(((MatchPublishedEvent) events.get(0)).matchId()).isEqualTo(match.getMatchId());
    }

    @Test
    void publishRejectsWhenAlreadyPublished() {
        Match match = matchWithShowtime();
        match.publish();

        assertThatThrownBy(match::publish)
                .isInstanceOf(InvalidMatchStatusException.class);
    }

    @Test
    void completeTransitionsFromPublishedAndRaisesEventWithShowtimeIds() {
        Match match = matchWithShowtime();
        match.publish();
        match.pullDomainEvents();
        String showtimeId = match.getShowtimes().get(0).getShowtimeId();

        match.complete();

        assertThat(match.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(match.pullDomainEvents()).hasSize(1)
                .first().satisfies(event -> {
                    var completed = (com.aireak.catalog.domain.event.MatchCompletedEvent) event;
                    assertThat(completed.matchId()).isEqualTo(match.getMatchId());
                    assertThat(completed.showtimeIds()).containsExactly(showtimeId);
                });
    }

    @Test
    void completeRejectsWhenStillDraft() {
        Match match = matchWithShowtime();

        assertThatThrownBy(match::complete)
                .isInstanceOf(InvalidMatchStatusException.class);
    }

    @Test
    void cancelAllowedFromDraft() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");

        match.cancel("Venue unavailable");

        assertThat(match.getStatus()).isEqualTo(MatchStatus.CANCELLED);
    }

    @Test
    void cancelAllowedFromPublishedAndRaisesEventWithShowtimeIdsAndReason() {
        Match match = matchWithShowtime();
        match.publish();
        match.pullDomainEvents();
        String showtimeId = match.getShowtimes().get(0).getShowtimeId();

        match.cancel("Stadium closed for safety inspection");

        assertThat(match.getStatus()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(match.pullDomainEvents()).hasSize(1)
                .first().satisfies(event -> {
                    var cancelled = (com.aireak.catalog.domain.event.MatchCancelledEvent) event;
                    assertThat(cancelled.matchId()).isEqualTo(match.getMatchId());
                    assertThat(cancelled.showtimeIds()).containsExactly(showtimeId);
                    assertThat(cancelled.reason()).isEqualTo("Stadium closed for safety inspection");
                });
    }

    @Test
    void cancelRejectsWhenAlreadyCompleted() {
        Match match = matchWithShowtime();
        match.publish();
        match.complete();

        assertThatThrownBy(() -> match.cancel("too late"))
                .isInstanceOf(InvalidMatchStatusException.class);
    }

    @Test
    void cancelRejectsWhenAlreadyCancelled() {
        Match match = Match.create("Home FC", "Away FC", "Premier League");
        match.cancel("first cancellation");

        assertThatThrownBy(() -> match.cancel("second cancellation"))
                .isInstanceOf(InvalidMatchStatusException.class);
    }

    @Test
    void reconstitutePreservesStateAndRaisesNoEvents() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        Showtime showtime = new Showtime("showtime-1", Instant.now(), "venue-1", 100, 80, BASE_PRICE, "VND");

        Match match = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, createdAt, List.of(showtime));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PUBLISHED);
        assertThat(match.getCreatedAt()).isEqualTo(createdAt);
        assertThat(match.getShowtimes()).containsExactly(showtime);
        assertThat(match.pullDomainEvents()).isEmpty();
    }

    @Test
    void pullDomainEventsClearsTheList() {
        Match match = matchWithShowtime();
        match.publish();

        List<Object> firstPull = match.pullDomainEvents();
        List<Object> secondPull = match.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }
}
