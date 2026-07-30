package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.config.InfraConfig;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.Showtime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MatchCatalogService#addShowtime's own existsShowtimeAtVenueAndTime() pre-check is a plain
 * check-then-act — under two genuinely concurrent requests both can pass it before either
 * commits. This test bypasses that application-level check entirely (unlike
 * MatchCatalogServiceTest/OutboxEventPublisherIntegrationTest, which only ever go through the
 * service and so can never reach this code path) and saves two separate Match aggregates with a
 * showtime at the identical venue/time directly through the persistence adapter, proving the
 * uk_showtimes_venue_time DB constraint (see V4 migration) is the real backstop against the race.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        InfraConfig.class, // @EnableJpaAuditing — see OutboxEventPublisherIntegrationTest for why
        MatchPersistenceAdapter.class
})
class ShowtimeVenueTimeUniqueConstraintIntegrationTest {

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
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired
    private MatchPersistenceAdapter matchPersistenceAdapter;

    // Package-private, same package as this test — used only to force a flush (save() alone
    // defers the physical INSERT via Hibernate's write-behind, so the constraint violation would
    // otherwise only surface at the test transaction's implicit end-of-test flush, too late for
    // assertThatThrownBy to observe it).
    @Autowired
    private MatchJpaRepository matchJpaRepository;

    @Test
    void persistingTwoShowtimesAtTheSameVenueAndTimeViolatesTheUniqueConstraint() {
        Instant startTime = Instant.now().plusSeconds(3600);

        Match matchA = Match.create("Home FC", "Away FC", "Premier League");
        matchA.addShowtime(new Showtime(startTime, "venue-1", 100, new BigDecimal("150000"), "VND"));
        matchPersistenceAdapter.save(matchA);
        matchJpaRepository.flush();

        Match matchB = Match.create("Other FC", "Another FC", "Premier League");
        matchB.addShowtime(new Showtime(startTime, "venue-1", 100, new BigDecimal("150000"), "VND"));
        matchPersistenceAdapter.save(matchB);

        assertThatThrownBy(() -> matchJpaRepository.flush())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
