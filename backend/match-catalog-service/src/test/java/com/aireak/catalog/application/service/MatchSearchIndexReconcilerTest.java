package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchException;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchSearchIndexReconcilerTest {

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchSearchPort matchSearchPort;

    private MatchSearchIndexReconciler reconciler() {
        return new MatchSearchIndexReconciler(matchRepository, matchSearchPort);
    }

    @Test
    void indexesAPublishedMatch() {
        Match published = match("match-1", MatchStatus.PUBLISHED);
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(published));

        reconciler().reconcile("match-1");

        verify(matchSearchPort).index(published);
        verify(matchSearchPort, never()).delete(any());
    }

    /**
     * Cancelled and completed matches leave the index rather than being re-indexed with their
     * new status: the search query only ever asks for PUBLISHED documents, so a deleted document
     * and a filtered one look the same to a caller, and a deleted one cannot go stale. The same
     * rule keeps a cancelled DRAFT out — it was never public, and deleting a document that was
     * never written is the adapter's no-op.
     */
    @Test
    void neverIndexesAMatchThatIsNotPublished() {
        for (MatchStatus status : List.of(MatchStatus.DRAFT, MatchStatus.CANCELLED, MatchStatus.COMPLETED)) {
            String id = "match-" + status;
            when(matchRepository.findByIdForUpdate(id)).thenReturn(Optional.of(match(id, status)));

            reconciler().reconcile(id);

            verify(matchSearchPort).delete(id);
        }
        verify(matchSearchPort, never()).index(any());
    }

    /**
     * The database row is the source of truth, not the event that triggered the call — a
     * MatchPublishedEvent replayed after the match was cancelled must delete, not resurrect.
     * The reconciler cannot see which event it was called for, which is exactly what makes this
     * hold: there is only one input, the match id, and one answer per row.
     */
    @Test
    void writesWhatTheDatabaseSaysNotWhatTheEventSaid() {
        when(matchRepository.findByIdForUpdate("match-1"))
                .thenReturn(Optional.of(match("match-1", MatchStatus.CANCELLED)));

        reconciler().reconcile("match-1");

        verify(matchSearchPort).delete("match-1");
        verify(matchSearchPort, never()).index(any());
    }

    /** Nothing to index and nothing to correct: not worth the retries a throw would trigger. */
    @Test
    void skipsAMatchIdThatDoesNotResolveWithoutThrowing() {
        when(matchRepository.findByIdForUpdate("gone")).thenReturn(Optional.empty());

        assertThatCode(() -> reconciler().reconcile("gone")).doesNotThrowAnyException();

        verify(matchSearchPort, never()).index(any());
        verify(matchSearchPort, never()).delete(any());
    }

    /**
     * The whole reason the index write moved here from an @Async pool: a failure must reach the
     * Kafka error handler, which retries and then dead-letters, instead of ending in a log line.
     * Swallowing it here would put the original silent gap back one layer down.
     */
    @Test
    void letsASearchFailurePropagateSoKafkaRetriesIt() {
        Match published = match("match-1", MatchStatus.PUBLISHED);
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(published));
        doThrow(new MatchSearchException("Failed to index match match-1", null))
                .when(matchSearchPort).index(published);

        assertThatThrownBy(() -> reconciler().reconcile("match-1"))
                .isInstanceOf(MatchSearchException.class);
    }

    private static Match match(String id, MatchStatus status) {
        return Match.reconstitute(id, "Home FC", "Away FC", "Premier League", status, Instant.now(), List.of());
    }
}
