package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.config.InfraConfig;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SQL statement count of every multi-root read path in {@link MatchPersistenceAdapter}.
 *
 * <p>These are the reads that used to be N+1: a page of matches was resolved one
 * {@code findById} at a time, and the id list behind it was obtained by fetch-joining every match
 * of a status together with all its showtimes and then discarding everything but the id. Nothing
 * about that was visible in a normal assertion — the results were correct, only the query count
 * was wrong — so the guard has to be the count itself.
 *
 * <p>{@link #theQueryCountDoesNotGrowWithThePageSize} is the one that actually catches a
 * regression: it reads a 2-match page and a 10-match page and requires the same number of
 * statements for both. Any reintroduced per-match query makes the two diverge.
 *
 * <p>Statements are counted with Hibernate's own {@code generate_statistics}, and the persistence
 * context is cleared before each measurement so a read is never quietly served from the
 * first-level cache.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        InfraConfig.class, // @EnableJpaAuditing — see OutboxEventPublisherIntegrationTest for why
        MatchPersistenceAdapter.class
})
class MatchReadQueryCountIntegrationTest {

    private static final int SEEDED_MATCHES = 10;
    private static final int SHOWTIMES_PER_MATCH = 3;

    /** Roots in one query, their showtimes in a second {@code WHERE match_id IN (...)}. */
    private static final long EXPECTED_MULTI_ROOT_STATEMENTS = 2;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("aireak")
            .withPassword("aireak");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired
    private MatchPersistenceAdapter adapter;

    @Autowired
    private MatchJpaRepository jpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;
    private List<String> seededIds;

    @BeforeEach
    void seedCatalog() {
        seededIds = new ArrayList<>();
        Instant slot = Instant.parse("2026-09-01T18:00:00Z");
        for (int i = 0; i < SEEDED_MATCHES; i++) {
            List<Showtime> showtimes = new ArrayList<>();
            for (int j = 0; j < SHOWTIMES_PER_MATCH; j++) {
                // uk_showtimes_venue_time is on (venue_id, start_time) — a distinct hour per
                // showtime keeps every row unique.
                showtimes.add(new Showtime(
                        "showtime-" + i + "-" + j,
                        slot.plus(i * SHOWTIMES_PER_MATCH + j, ChronoUnit.HOURS),
                        "stadium-1", 100, 100, new BigDecimal("150000"), "VND"));
            }
            String matchId = "match-" + i;
            adapter.save(Match.reconstitute(matchId, "Home FC " + i, "Away FC " + i, "V.League 1",
                    MatchStatus.PUBLISHED, Instant.now(), showtimes));
            seededIds.add(matchId);
        }
        jpaRepository.flush();

        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /** Runs a read with a cold persistence context and reports how many statements it cost. */
    private long statementsFor(Runnable read) {
        entityManager.clear();
        statistics.clear();
        read.run();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void theQueryCountDoesNotGrowWithThePageSize() {
        long forTwo = statementsFor(() -> adapter.findByStatus(MatchStatus.PUBLISHED, 0, 2));
        long forTen = statementsFor(() -> adapter.findByStatus(MatchStatus.PUBLISHED, 0, 10));

        assertThat(forTwo).isEqualTo(EXPECTED_MULTI_ROOT_STATEMENTS);
        assertThat(forTen)
                .as("a 10-match page must cost the same as a 2-match page; if it does not, "
                        + "something is querying per match again")
                .isEqualTo(forTwo);
    }

    @Test
    void aPageOfMatchesArrivesCompleteInTwoStatements() {
        List<Match> page = new ArrayList<>();
        long statements = statementsFor(() -> page.addAll(adapter.findByStatus(MatchStatus.PUBLISHED, 0, 5)));

        assertThat(statements).isEqualTo(EXPECTED_MULTI_ROOT_STATEMENTS);
        assertThat(page).hasSize(5);
        // Showtimes really are populated — the low statement count is not just an empty read.
        assertThat(page).allSatisfy(match ->
                assertThat(match.getShowtimes()).hasSize(SHOWTIMES_PER_MATCH));
    }

    @Test
    void findAllByIdsReadsEveryRequestedMatchInTwoStatementsAndKeepsTheGivenOrder() {
        List<String> requested = List.of("match-7", "match-2", "match-9", "match-0");
        List<Match> found = new ArrayList<>();

        long statements = statementsFor(() -> found.addAll(adapter.findAllByIds(requested)));

        assertThat(statements).isEqualTo(EXPECTED_MULTI_ROOT_STATEMENTS);
        assertThat(found).extracting(Match::getMatchId).containsExactlyElementsOf(requested);
        assertThat(found).allSatisfy(match ->
                assertThat(match.getShowtimes()).hasSize(SHOWTIMES_PER_MATCH));
    }

    @Test
    void findAllByIdsSkipsIdsThatNoLongerResolveInsteadOfFailing() {
        List<Match> found = new ArrayList<>();
        long statements = statementsFor(() ->
                found.addAll(adapter.findAllByIds(List.of("match-1", "gone", "match-3"))));

        assertThat(statements).isEqualTo(EXPECTED_MULTI_ROOT_STATEMENTS);
        assertThat(found).extracting(Match::getMatchId).containsExactly("match-1", "match-3");
    }

    @Test
    void theIdListCostsOneStatementAndTouchesNoShowtimes() {
        List<String> ids = new ArrayList<>();
        long statements = statementsFor(() -> ids.addAll(adapter.findIdsByStatus(MatchStatus.PUBLISHED)));

        assertThat(statements)
                .as("ids only: no join to showtimes, no entity hydration")
                .isEqualTo(1);
        assertThat(ids).hasSize(SEEDED_MATCHES).containsExactlyInAnyOrderElementsOf(seededIds);
    }

    @Test
    void aSingleMatchStillCostsOneStatementViaItsFetchJoin() {
        List<Match> found = new ArrayList<>();
        long statements = statementsFor(() -> adapter.findById("match-4").ifPresent(found::add));

        assertThat(statements)
                .as("one root: JOIN FETCH is the right tool and costs a single round-trip")
                .isEqualTo(1);
        assertThat(found).singleElement().satisfies(match ->
                assertThat(match.getShowtimes()).hasSize(SHOWTIMES_PER_MATCH));
    }

    @Test
    void anEmptyIdSetCostsNoQueriesAtAll() {
        List<Match> found = new ArrayList<>();
        long statements = statementsFor(() -> found.addAll(adapter.findAllByIds(List.of())));

        assertThat(statements).isZero();
        assertThat(found).isEmpty();
    }
}
