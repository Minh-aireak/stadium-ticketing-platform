package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.catalog.domain.exception.InvalidMatchStatusException;
import com.aireak.catalog.domain.exception.InvalidShowtimeException;
import com.aireak.catalog.domain.exception.MatchNotFoundException;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchCatalogServiceTest {

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchSearchPort matchSearchPort;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private ShowtimeSeatCounterInitializer showtimeSeatCounterInitializer;

    private MatchCatalogService service;

    @BeforeEach
    void setUp() {
        service = new MatchCatalogService(matchRepository, matchSearchPort, eventPublisher,
                showtimeSeatCounterInitializer);
    }

    private static final BigDecimal BASE_PRICE = new BigDecimal("150000");

    private Match matchWithShowtime(String matchId) {
        Match match = Match.reconstitute(matchId, "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(),
                List.of(new Showtime("showtime-1", Instant.now().plusSeconds(3600), "venue-1", 100, 100,
                        BASE_PRICE, "VND")));
        return match;
    }

    @Test
    void createMatchSavesAndReturnsGeneratedId() {
        String matchId = service.createMatch("Home FC", "Away FC", "Premier League");

        assertThat(matchId).isNotBlank();
        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getMatchId()).isEqualTo(matchId);
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.DRAFT);
    }

    @Test
    void addShowtimeLoadsMutatesSavesAndPublishesEvent() {
        Match existing = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(existing));
        Instant startTime = Instant.now().plusSeconds(7200);

        service.addShowtime("match-1", startTime, "my-dinh", BASE_PRICE, "VND");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getShowtimes()).hasSize(1);
        assertThat(saved.getValue().getShowtimes().get(0).getTotalSeats()).isEqualTo(432);

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(ShowtimeAddedEvent.class);
    }

    /**
     * The counter has to exist from the moment the showtime does — a browse request can arrive
     * before the first ticket is ever sold, and nothing else seeds it.
     */
    @Test
    void addShowtimeSeedsTheLiveSeatCounterWithTheStadiumsFullCapacity() {
        Match existing = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(existing));

        service.addShowtime("match-1", Instant.now().plusSeconds(7200), "my-dinh", BASE_PRICE, "VND");

        String showtimeId = existing.getShowtimes().get(0).getShowtimeId();
        verify(showtimeSeatCounterInitializer).initializeCounter(showtimeId, 432);
    }

    @Test
    void addShowtimeSeedsNoCounterWhenTheShowtimeIsRejected() {
        assertThatThrownBy(() -> service.addShowtime(
                "match-1", Instant.now().plusSeconds(3600), "unknown-stadium", BASE_PRICE, "VND"))
                .isInstanceOf(InvalidShowtimeException.class);

        verify(showtimeSeatCounterInitializer, never()).initializeCounter(any(), anyInt());
    }

    @Test
    void addShowtimeThrowsWhenMatchNotFound() {
        when(matchRepository.findByIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addShowtime(
                "missing", Instant.now().plusSeconds(3600), "my-dinh", BASE_PRICE, "VND"))
                .isInstanceOf(MatchNotFoundException.class);
    }

    @Test
    void addShowtimeThrowsWhenStartTimeIsNotInTheFuture() {
        assertThatThrownBy(() -> service.addShowtime(
                "match-1", Instant.now().minusSeconds(1), "my-dinh", BASE_PRICE, "VND"))
                .isInstanceOf(InvalidShowtimeException.class);

        verify(matchRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void addShowtimeRejectsUnknownStadiumBeforePersistingAnything() {
        assertThatThrownBy(() -> service.addShowtime(
                "match-1", Instant.now().plusSeconds(3600), "unknown-stadium", BASE_PRICE, "VND"))
                .isInstanceOf(InvalidShowtimeException.class)
                .hasMessageContaining("Unknown stadium");

        verify(matchRepository, never()).findByIdForUpdate(any());
        verify(matchRepository, never()).save(any());
    }

    @Test
    void addShowtimeThrowsWhenVenueAlreadyHasAShowtimeAtThatStartTime() {
        Instant startTime = Instant.now().plusSeconds(3600);
        when(matchRepository.existsShowtimeAtVenueAndTime("my-dinh", startTime)).thenReturn(true);

        assertThatThrownBy(() -> service.addShowtime(
                "match-1", startTime, "my-dinh", BASE_PRICE, "VND"))
                .isInstanceOf(InvalidShowtimeException.class);

        verify(matchRepository, never()).save(any());
    }

    /**
     * No search-index write is expected here, and that is the point: the index is written by
     * MatchSearchIndexConsumer off the MatchPublishedEvent this publishes through the outbox
     * (see MatchSearchIndexReconciler), so the event IS the index write — asserting on it is
     * asserting that the match will be searchable.
     */
    @Test
    void publishMatchSavesAndPublishesTheEventThatIndexesIt() {
        Match existing = matchWithShowtime("match-1");
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(existing));

        service.publishMatch("match-1");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.PUBLISHED);

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(MatchPublishedEvent.class);
        assertThat(((MatchPublishedEvent) published.getValue().get(0)).matchId()).isEqualTo("match-1");
        verify(matchSearchPort, never()).index(any());
    }

    @Test
    void publishMatchPropagatesDomainInvariantViolationWithoutPublishing() {
        // No showtimes attached — Match.publish() enforces this invariant, the service must not
        // swallow it or index/publish a match that never actually transitioned.
        Match draftWithNoShowtimes = Match.reconstitute("match-1", "Home FC", "Away FC",
                "Premier League", MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(draftWithNoShowtimes));

        assertThatThrownBy(() -> service.publishMatch("match-1"))
                .isInstanceOf(InvalidMatchStatusException.class);

        verify(matchRepository, never()).save(any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void completeMatchTransitionsSavesAndPublishesEvent() {
        Match published = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(published));

        service.completeMatch("match-1");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.COMPLETED);

        ArgumentCaptor<List<Object>> published2 = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published2.capture());
        assertThat(published2.getValue()).hasSize(1);
        assertThat(published2.getValue().get(0)).isInstanceOf(com.aireak.catalog.domain.event.MatchCompletedEvent.class);
    }

    @Test
    void cancelMatchTransitionsSavesAndPublishesEventWithShowtimeIdsAndReason() {
        Match published = matchWithShowtime("match-1");
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(published));

        service.cancelMatch("match-1", "Stadium closed for safety inspection");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.CANCELLED);

        ArgumentCaptor<List<Object>> published2 = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published2.capture());
        assertThat(published2.getValue()).hasSize(1);
        var event = (com.aireak.catalog.domain.event.MatchCancelledEvent) published2.getValue().get(0);
        assertThat(event.showtimeIds()).containsExactly("showtime-1");
        assertThat(event.reason()).isEqualTo("Stadium closed for safety inspection");
    }

    /**
     * The index used to be written on publish() and never again, which is what made
     * listMatches report a total that counted matches it had already filtered out of the page.
     * The correction now rides MatchCompletedEvent: MatchSearchIndexReconciler deletes the
     * document of any non-PUBLISHED match it is asked about, so the event is all this has to emit.
     */
    @Test
    void completeMatchPublishesTheEventThatRemovesItFromSearch() {
        Match published = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(published));

        service.completeMatch("match-1");

        ArgumentCaptor<List<Object>> published2 = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published2.capture());
        assertThat(published2.getValue()).singleElement()
                .isInstanceOf(com.aireak.catalog.domain.event.MatchCompletedEvent.class);
        verify(matchSearchPort, never()).index(any());
        verify(matchSearchPort, never()).delete(any());
    }

    /**
     * A DRAFT match can be cancelled too, and was never indexed. The service no longer decides
     * whether a document exists to correct: it emits MatchCancelledEvent either way, and the
     * reconciler deletes for any non-PUBLISHED match, which is a no-op for a document that was
     * never written (MatchSearchIndexReconcilerTest covers that side). What this must hold is
     * that the event goes out for the draft as well, or a cancellation could never reach the
     * index at all.
     */
    @Test
    void cancelMatchPublishesTheEventForAPublishedMatchAndForADraft() {
        Match published = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(published));
        Match draft = matchWithShowtime("match-2");
        when(matchRepository.findByIdForUpdate("match-2")).thenReturn(Optional.of(draft));

        service.cancelMatch("match-1", "Stadium closed");
        service.cancelMatch("match-2", "Never went on sale");

        verify(eventPublisher).publishAll(argThat(events -> events.size() == 1
                && events.get(0) instanceof com.aireak.catalog.domain.event.MatchCancelledEvent e
                && "match-1".equals(e.matchId())));
        verify(eventPublisher).publishAll(argThat(events -> events.size() == 1
                && events.get(0) instanceof com.aireak.catalog.domain.event.MatchCancelledEvent e
                && "match-2".equals(e.matchId())));
        verify(matchSearchPort, never()).index(any());
        verify(matchSearchPort, never()).delete(any());
    }

    @Test
    void cancelMatchThrowsWhenMatchNotFound() {
        when(matchRepository.findByIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelMatch("missing", "reason"))
                .isInstanceOf(MatchNotFoundException.class);
    }

    @Test
    void completeMatchThrowsWhenMatchNotFound() {
        when(matchRepository.findByIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeMatch("missing"))
                .isInstanceOf(MatchNotFoundException.class);
    }

    /**
     * The write path must not read through {@link com.aireak.catalog.adapter.out.persistence.CachingMatchRepository}.
     * What {@code findById} returns has its {@code availableSeats} overwritten from the live Redis
     * counter, and the aggregate handed back here is saved straight back to Postgres — so reading
     * the write path through the read cache persisted a cache value over the column that cache is
     * a cache of.
     */
    @Test
    void everyWriteUseCaseLoadsTheAggregateOffTheWritePathReadNotTheCachedOne() {
        Match draft = matchWithShowtime("match-1");
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(draft));

        service.publishMatch("match-1");

        verify(matchRepository).findByIdForUpdate("match-1");
        verify(matchRepository, never()).findById(any());
    }

    @Test
    void listMatchesWithBlankQueryListsPublishedFromRepository() {
        Match published = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        when(matchRepository.findByStatus(MatchStatus.PUBLISHED, 0, 20)).thenReturn(List.of(published));
        when(matchRepository.countByStatus(MatchStatus.PUBLISHED)).thenReturn(1L);

        ListMatchesUseCase.MatchPage page = service.listMatches(null, 0, 20);

        assertThat(page.items()).containsExactly(published);
        assertThat(page.totalElements()).isEqualTo(1L);
        verify(matchSearchPort, never()).search(any(), anyInt(), anyInt());
    }

    @Test
    void listMatchesWithQueryDelegatesToSearchThenHydratesFromRepository() {
        Match stub = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        Match hydrated = matchWithShowtime("match-1");
        // publish() only ever leaves the aggregate DRAFT or PUBLISHED in these fixtures — force it
        // PUBLISHED here since listMatches() must filter out anything else after re-hydration.
        Match hydratedPublished = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, hydrated.getCreatedAt(), hydrated.getShowtimes());
        when(matchSearchPort.search("Home", 0, 20))
                .thenReturn(new MatchSearchPort.SearchResult(List.of(stub), 1));
        when(matchRepository.findAllByIds(List.of("match-1"))).thenReturn(List.of(hydratedPublished));

        ListMatchesUseCase.MatchPage page = service.listMatches("Home", 0, 20);

        assertThat(page.items()).containsExactly(hydratedPublished);
        assertThat(page.totalElements()).isEqualTo(1L);
        verify(matchRepository, never()).findByStatus(any(), anyInt(), anyInt());
    }

    @Test
    void listMatchesWithQueryHonorsThePageAndSizeInsteadOfReturningEveryHitAsOnePage() {
        // Test that page/size are passed directly to matchSearchPort.search()
        Match match2 = publishedStub("match-2");
        when(matchSearchPort.search("Home", 1, 1))
                .thenReturn(new MatchSearchPort.SearchResult(List.of(match2), 3));
        when(matchRepository.findAllByIds(List.of("match-2"))).thenReturn(List.of(match2));

        ListMatchesUseCase.MatchPage page = service.listMatches("Home", 1, 1);

        assertThat(page.items()).containsExactly(match2);
        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
    }

    private static Match publishedStub(String matchId) {
        return Match.reconstitute(matchId, "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
    }

    @Test
    void listMatchesWithQueryDropsHitsThatAreNoLongerPublished() {
        Match stub = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        Match nowCancelled = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.CANCELLED, Instant.now(), List.of());
        when(matchSearchPort.search("Home", 0, 20))
                .thenReturn(new MatchSearchPort.SearchResult(List.of(stub), 1));
        when(matchRepository.findAllByIds(List.of("match-1"))).thenReturn(List.of(nowCancelled));

        ListMatchesUseCase.MatchPage page = service.listMatches("Home", 0, 20);

        assertThat(page.items()).isEmpty();
    }

    @Test
    void listMatchesWithQueryRehydratesEveryHitInOneRepositoryCall() {
        List<Match> hits = List.of(publishedStub("match-1"), publishedStub("match-2"), publishedStub("match-3"));
        when(matchSearchPort.search("Home", 0, 20))
                .thenReturn(new MatchSearchPort.SearchResult(hits, 3));
        when(matchRepository.findAllByIds(List.of("match-1", "match-2", "match-3"))).thenReturn(hits);

        ListMatchesUseCase.MatchPage page = service.listMatches("Home", 0, 20);

        // Relevance order from Elasticsearch survives the re-read.
        assertThat(page.items()).extracting(Match::getMatchId)
                .containsExactly("match-1", "match-2", "match-3");
        // The N+1 guard: three hits, one re-read — not one findById per hit.
        verify(matchRepository, never()).findById(any());
    }

    @Test
    void getMatchReturnsPublishedMatch() {
        Match published = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(published));

        assertThat(service.getMatch("match-1")).contains(published);
    }

    @Test
    void getMatchHidesDraftMatches() {
        Match draft = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(draft));

        assertThat(service.getMatch("match-1")).isEmpty();
    }

    @Test
    void getMatchReturnsEmptyWhenNotFound() {
        when(matchRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.getMatch("missing")).isEmpty();
    }
}
