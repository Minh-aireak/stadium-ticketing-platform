package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.exception.InvalidMatchStatusException;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private MatchCatalogService service;

    @BeforeEach
    void setUp() {
        service = new MatchCatalogService(matchRepository, matchSearchPort, eventPublisher);
    }

    private Match matchWithShowtime(String matchId) {
        Match match = Match.reconstitute(matchId, "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(),
                List.of(new Showtime("showtime-1", Instant.now().plusSeconds(3600), "venue-1", 100, 100)));
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
    void addShowtimeLoadsMutatesAndSaves() {
        Match existing = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(existing));
        Instant startTime = Instant.now().plusSeconds(7200);

        service.addShowtime("match-1", startTime, "venue-1", 50);

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getShowtimes()).hasSize(1);
        assertThat(saved.getValue().getShowtimes().get(0).getTotalSeats()).isEqualTo(50);
    }

    @Test
    void addShowtimeThrowsWhenMatchNotFound() {
        when(matchRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addShowtime("missing", Instant.now(), "venue-1", 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishMatchSavesIndexesAndPublishesEvent() {
        Match existing = matchWithShowtime("match-1");
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(existing));

        service.publishMatch("match-1");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.PUBLISHED);

        verify(matchSearchPort).index(eq(saved.getValue()));

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(MatchPublishedEvent.class);
    }

    @Test
    void publishMatchPropagatesDomainInvariantViolationWithoutIndexingOrPublishing() {
        // No showtimes attached — Match.publish() enforces this invariant, the service must not
        // swallow it or index/publish a match that never actually transitioned.
        Match draftWithNoShowtimes = Match.reconstitute("match-1", "Home FC", "Away FC",
                "Premier League", MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(draftWithNoShowtimes));

        assertThatThrownBy(() -> service.publishMatch("match-1"))
                .isInstanceOf(InvalidMatchStatusException.class);

        verify(matchRepository, never()).save(any());
        verify(matchSearchPort, never()).index(any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void completeMatchTransitionsAndSaves() {
        Match published = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.PUBLISHED, Instant.now(), List.of());
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(published));

        service.completeMatch("match-1");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.COMPLETED);
    }

    @Test
    void cancelMatchTransitionsAndSaves() {
        Match draft = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(), List.of());
        when(matchRepository.findById("match-1")).thenReturn(Optional.of(draft));

        service.cancelMatch("match-1");

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MatchStatus.CANCELLED);
    }

    @Test
    void completeMatchThrowsWhenMatchNotFound() {
        when(matchRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeMatch("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
