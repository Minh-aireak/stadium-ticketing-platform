package com.aireak.inventory.adapter.out.persistence;

import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.config.InfraConfig;
import com.aireak.inventory.domain.exception.SeatAlreadySoldException;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatStatus;
import com.aireak.inventory.domain.model.SeatTier;
import jakarta.persistence.EntityManager;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the cost of confirming a sale, which used to scale with stadium capacity rather than with
 * the number of seats sold: the aggregate's seat collection is EAGER, so reading it to sell two
 * seats loaded every seat of the showtime, rebuilt all of them as domain objects, and diffed the
 * lot to find the two that changed — on every successful payment.
 *
 * <p>Nothing about that was visible in an ordinary assertion; the result was always correct, only
 * the work was wrong. So the guard has to be the load count itself, measured with Hibernate's own
 * {@code generate_statistics} — the same approach match-catalog-service uses for its read paths.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InfraConfig.class, SeatInventoryPersistenceAdapter.class})
class SeatSaleLoadCountIntegrationTest {

    private static final int SEAT_COUNT = 200;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_db")
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
    private SeatInventoryRepository seatInventoryRepository;

    @Autowired
    private EntityManager entityManager;

    private Statistics statistics;

    private static SeatInventory inventoryOf(String showtimeId) {
        List<Seat> seats = new ArrayList<>(SEAT_COUNT);
        for (int i = 1; i <= SEAT_COUNT; i++) {
            seats.add(new Seat(new SeatCode("A" + i), SeatTier.STANDARD, new BigDecimal("150000")));
        }
        return SeatInventory.create(showtimeId, seats);
    }

    @BeforeEach
    void resetStatistics() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /** Clears both caches so a measurement is never quietly served from the persistence context. */
    private void startMeasuring() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }

    @Test
    void confirmingASaleLoadsOnlyTheSeatsBeingSold() {
        seatInventoryRepository.save(inventoryOf("showtime-load-count"));
        startMeasuring();

        List<SeatCode> sold = List.of(new SeatCode("A7"), new SeatCode("A8"));
        SeatInventory inventory = seatInventoryRepository
                .findByShowtimeIdWithSeats("showtime-load-count", sold).orElseThrow();
        inventory.sellSeats(sold, "booking-1");
        seatInventoryRepository.saveSeats(inventory);

        // Exactly the seats being sold, and nothing that grows with SEAT_COUNT. The previous path
        // loaded the aggregate root plus all 200 seats for the same two-row update; this assertion
        // is what stops that coming back.
        assertThat(statistics.getEntityLoadCount())
                .as("entities loaded to sell %d of %d seats", sold.size(), SEAT_COUNT)
                .isEqualTo(sold.size());
    }

    /** The partial load must still persist the sale — cheap is only useful if it is also correct. */
    @Test
    void confirmingASalePersistsExactlyThoseSeatsAsSold() {
        seatInventoryRepository.save(inventoryOf("showtime-persist-check"));

        List<SeatCode> sold = List.of(new SeatCode("A7"), new SeatCode("A8"));
        SeatInventory inventory = seatInventoryRepository
                .findByShowtimeIdWithSeats("showtime-persist-check", sold).orElseThrow();
        inventory.sellSeats(sold, "booking-1");
        seatInventoryRepository.saveSeats(inventory);
        entityManager.flush();
        entityManager.clear();

        SeatInventory reloaded = seatInventoryRepository.findByShowtimeId("showtime-persist-check").orElseThrow();
        assertThat(reloaded.getSeats())
                .filteredOn(seat -> seat.getStatus() == SeatStatus.SOLD)
                .extracting(seat -> seat.getSeatCode().value())
                .containsExactlyInAnyOrder("A7", "A8");
        assertThat(reloaded.getSeats()).hasSize(SEAT_COUNT);
    }

    /**
     * The protection that actually stands behind the Redisson lock, now that the aggregate root's
     * inert {@code @Version} is gone: a second booking claiming a SOLD seat is rejected on the
     * seat's own persisted state, not on a version counter.
     */
    @Test
    void aDifferentBookingCannotTakeASeatThatIsAlreadySold() {
        seatInventoryRepository.save(inventoryOf("showtime-double-book"));
        List<SeatCode> seat = List.of(new SeatCode("A7"));

        SeatInventory first = seatInventoryRepository
                .findByShowtimeIdWithSeats("showtime-double-book", seat).orElseThrow();
        first.sellSeats(seat, "booking-1");
        seatInventoryRepository.saveSeats(first);
        entityManager.flush();
        entityManager.clear();

        SeatInventory second = seatInventoryRepository
                .findByShowtimeIdWithSeats("showtime-double-book", seat).orElseThrow();
        assertThatThrownBy(() -> second.sellSeats(seat, "booking-2"))
                .isInstanceOf(SeatAlreadySoldException.class);
    }

    /** Re-delivering the same confirmation is a no-op, not a failure — the sale already happened. */
    @Test
    void reConfirmingTheSameBookingIsIdempotent() {
        seatInventoryRepository.save(inventoryOf("showtime-redeliver"));
        List<SeatCode> seat = List.of(new SeatCode("A7"));

        SeatInventory first = seatInventoryRepository
                .findByShowtimeIdWithSeats("showtime-redeliver", seat).orElseThrow();
        first.sellSeats(seat, "booking-1");
        seatInventoryRepository.saveSeats(first);
        entityManager.flush();
        entityManager.clear();

        SeatInventory replay = seatInventoryRepository
                .findByShowtimeIdWithSeats("showtime-redeliver", seat).orElseThrow();
        replay.sellSeats(seat, "booking-1");

        assertThat(replay.pullDomainEvents())
                .as("a replay must not re-announce the sale")
                .isEmpty();
    }

    /** A showtime with no seat map at all still reads as not-found, rather than as zero seats. */
    @Test
    void anUnknownShowtimeIsNotFound() {
        assertThat(seatInventoryRepository.findByShowtimeIdWithSeats("no-such-showtime",
                List.of(new SeatCode("A1")))).isEmpty();
    }
}
