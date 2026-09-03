package com.aireak.inventory.adapter.out.lock;

import com.aireak.inventory.domain.exception.SeatsNotAvailableException;
import com.aireak.inventory.domain.model.SeatCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link RedissonSeatHoldAdapter} against a real Redis (Testcontainers) — the
 * confirmed-hold owner encoding (customerId + bookingId, see the adapter's class javadoc) that
 * backs {@link com.aireak.inventory.application.port.out.SeatHoldPort#isFreeOfHoldsByOtherOwners}
 * — the check that a customer-token release actually owns the reservation it is releasing — is
 * exactly the kind of string-format detail worth checking against real Redis rather than mocking.
 */
@Testcontainers
class RedissonSeatHoldAdapterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final String SHOWTIME_ID = "showtime-1";

    static RedissonClient redissonClient;
    static RedissonSeatHoldAdapter adapter;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        adapter = new RedissonSeatHoldAdapter(redissonClient);
    }

    @AfterAll
    static void tearDown() {
        redissonClient.shutdown();
    }

    @Test
    void confirmHoldHandsOverAStandaloneHoldAndIsHeldByCustomerAndBookingRecognisesItsOwner() {
        String showtimeId = uniqueShowtime();
        List<SeatCode> seats = List.of(new SeatCode("A1"));
        String customerId = "customer-1";
        String bookingId = "booking-1";

        adapter.holdSeats(showtimeId, seats, customerId);
        adapter.confirmHold(showtimeId, seats, customerId, bookingId);

        assertThat(adapter.isFreeOfHoldsByOtherOwners(showtimeId, seats, customerId, bookingId)).isTrue();
    }

    @Test
    void isFreeOfHoldsByOtherOwnersRejectsADifferentCustomerOrBooking() {
        String showtimeId = uniqueShowtime();
        List<SeatCode> seats = List.of(new SeatCode("A1"));
        adapter.confirmHold(showtimeId, seats, "customer-1", "booking-1");

        // Wrong customer, correct booking — an attacker who somehow learned the real bookingId.
        assertThat(adapter.isFreeOfHoldsByOtherOwners(showtimeId, seats, "attacker", "booking-1")).isFalse();
        // Correct customer, wrong booking.
        assertThat(adapter.isFreeOfHoldsByOtherOwners(showtimeId, seats, "customer-1", "booking-2")).isFalse();
    }

    @Test
    void isFreeOfHoldsByOtherOwnersPassesVacuouslyWhenNoHoldIsActive() {
        String showtimeId = uniqueShowtime();
        List<SeatCode> seats = List.of(new SeatCode("A1"));

        assertThat(adapter.isFreeOfHoldsByOtherOwners(showtimeId, seats, "customer-1", "booking-1")).isTrue();
    }

    @Test
    void releaseHoldsRemovesAConfirmedHoldByItsBookingId() {
        String showtimeId = uniqueShowtime();
        List<SeatCode> seats = List.of(new SeatCode("A1"));
        adapter.confirmHold(showtimeId, seats, "customer-1", "booking-1");

        adapter.releaseHolds(showtimeId, seats, "booking-1");

        assertThat(adapter.findHoldOwners(showtimeId, seats)).isEmpty();
    }

    @Test
    void releaseHoldsDoesNotRemoveAHoldOwnedByADifferentBooking() {
        String showtimeId = uniqueShowtime();
        List<SeatCode> seats = List.of(new SeatCode("A1"));
        adapter.confirmHold(showtimeId, seats, "customer-1", "booking-1");

        adapter.releaseHolds(showtimeId, seats, "booking-2");

        assertThat(adapter.findHoldOwners(showtimeId, seats)).containsOnlyKeys(new SeatCode("A1"));
    }

    @Test
    void releaseHoldsStillReleasesAPlainStandaloneHoldByCustomerId() {
        String showtimeId = uniqueShowtime();
        List<SeatCode> seats = List.of(new SeatCode("A1"));
        adapter.holdSeats(showtimeId, seats, "customer-1");

        adapter.releaseHolds(showtimeId, seats, "customer-1");

        assertThat(adapter.findHoldOwners(showtimeId, seats)).isEmpty();
    }

    /**
     * A reserve that fails on one seat must not cost the customer the seats it did not fail on.
     *
     * <p>The frontend holds seats one at a time as they are clicked (see SeatSelectionPage), so a
     * selection's holds expire at staggered times: one seat going stale while the rest are still
     * live is the ordinary case, not an exotic one. The rollback used to delete every key it had
     * written, which for a handed-over seat meant deleting a hold the customer still owned rather
     * than putting it back the way it was found.
     */
    @Test
    void aFailedConfirmHoldRestoresTheCustomersOwnPreBookingHold() {
        String showtimeId = uniqueShowtime();
        SeatCode stillMine = new SeatCode("A1");
        SeatCode goneToSomeoneElse = new SeatCode("A2");

        adapter.holdSeats(showtimeId, List.of(stillMine), "customer-1");
        adapter.holdSeats(showtimeId, List.of(goneToSomeoneElse), "customer-2");

        assertThatThrownBy(() -> adapter.confirmHold(
                showtimeId, List.of(stillMine, goneToSomeoneElse), "customer-1", "booking-1"))
                .isInstanceOf(SeatsNotAvailableException.class);

        assertThat(adapter.findHoldOwners(showtimeId, List.of(stillMine, goneToSomeoneElse)))
                .containsEntry(stillMine, "customer-1")
                .containsEntry(goneToSomeoneElse, "customer-2");
    }

    /**
     * The other half of the rollback contract, so restoring handed-over holds cannot quietly turn
     * into restoring everything: a seat this call placed from scratch had no owner before it and
     * must be left with none.
     */
    @Test
    void aFailedConfirmHoldStillRemovesTheHoldsItPlacedFromScratch() {
        String showtimeId = uniqueShowtime();
        SeatCode neverHeldBefore = new SeatCode("A1");
        SeatCode goneToSomeoneElse = new SeatCode("A2");

        adapter.holdSeats(showtimeId, List.of(goneToSomeoneElse), "customer-2");

        assertThatThrownBy(() -> adapter.confirmHold(
                showtimeId, List.of(neverHeldBefore, goneToSomeoneElse), "customer-1", "booking-1"))
                .isInstanceOf(SeatsNotAvailableException.class);

        assertThat(adapter.findHoldOwners(showtimeId, List.of(neverHeldBefore, goneToSomeoneElse)))
                .containsOnlyKeys(goneToSomeoneElse);
    }

    private static String uniqueShowtime() {
        return SHOWTIME_ID + "-" + UUID.randomUUID();
    }
}
