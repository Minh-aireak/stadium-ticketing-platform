package com.aireak.payment.application.service;

import com.aireak.payment.adapter.out.idempotency.RedissonPaymentIdempotencyAdapter;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.config.PaymentModeProperties;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.dao.QueryTimeoutException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lifetime of the payment idempotency guard, against a real Redis — the question is whether a
 * key is still set after an attempt that acquired it went away, which is Redis's own state and not
 * something a mocked port can answer honestly.
 *
 * <p>Every attempt gets a <strong>fresh</strong> {@link RedissonPaymentIdempotencyAdapter}, i.e. a
 * different pod (or the same pod after a restart). That empties the adapter's per-JVM
 * {@code paymentIdCache}, so the distributed guard is the only thing arbitrating and a passing
 * test cannot be an artifact of a local cache hit.
 *
 * <p>Assertions are behavioural — what a retry of {@code POST /api/v1/payments} actually gets
 * back — rather than reads of the Redis key, so they stay true to what the product does and do not
 * hard-code {@code PaymentService}'s private key prefix.
 */
@Testcontainers
@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyGuardReleaseTest {

    private static final PaymentModeProperties AUTO_MODE =
            new PaymentModeProperties(PaymentModeProperties.Mode.AUTO, null);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedissonClient redissonClient;

    @Mock
    private PaymentSagaSteps sagaSteps;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGatewayPort paymentGatewayPort;
    @Mock
    private PaymentReconciliationPort reconciliationPort;

    @BeforeAll
    static void startRedis() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void stopRedis() {
        redissonClient.shutdown();
    }

    // A separate service instance per attempt — see the class javadoc for why that matters.
    private PaymentService attempt() {
        return new PaymentService(sagaSteps, paymentRepository, paymentGatewayPort,
                new RedissonPaymentIdempotencyAdapter(redissonClient), reconciliationPort, AUTO_MODE);
    }

    // Distinct per test: the container is static, so the guard Redis holds outlives each test.
    private static InitiatePaymentCommand commandFor(String bookingId) {
        return new InitiatePaymentCommand(bookingId, "buyer@example.com", new BigDecimal("100.00"), "USD");
    }

    /**
     * The failure this whole class exists for. booking-service re-sends {@code POST
     * /api/v1/payments} on a 500 ({@code @Retry(name = "payment")}, maxAttempts 3, waitDuration
     * 500ms — a 500 is not in its {@code ignoreExceptions}), so the re-send lands far inside the
     * guard's 5-minute TTL. If the first attempt's guard is still held, the re-send is refused for
     * a payment that does not exist and never will.
     */
    @Test
    void aDraftRowThatCannotBeCommittedHandsTheGuardBackSoTheRetryChargesInstead() {
        InitiatePaymentCommand command = commandFor("booking-draft-failed");
        when(sagaSteps.tryInitiate(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("statement timeout"))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));

        assertThatThrownBy(() -> attempt().execute(command))
                .isInstanceOf(QueryTimeoutException.class);

        // The retry must get a real second attempt, not an answer about the first one.
        assertThat(attempt().execute(command)).isEqualTo("payment-1");
        verify(sagaSteps, times(2)).tryInitiate(any(), any(), any(), any());
    }

    /**
     * The same hole reached without any database outage: {@code tryInsert} maps every
     * {@code DataIntegrityViolationException} to {@code AlreadyExists}, so a constraint other than
     * {@code uq_payments_booking_id} lands here with no row to find. This attempt owns the guard
     * (it was granted {@code Acquired}), so it must hand it back on the way out.
     */
    @Test
    void aDraftRowRefusedWithNoPaymentToShowForItHandsTheGuardBackToo() {
        InitiatePaymentCommand command = commandFor("booking-draft-refused");
        when(sagaSteps.tryInitiate(any(), any(), any(), any()))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.AlreadyExists())
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-2"));

        assertThatThrownBy(() -> attempt().execute(command))
                .isInstanceOf(DuplicatePaymentException.class);

        assertThat(attempt().execute(command)).isEqualTo("payment-2");
        verify(sagaSteps, times(2)).tryInitiate(any(), any(), any(), any());
    }

    /**
     * The other half of the rule, and the one a release is capable of breaking: a guard reported
     * as {@code AlreadyHeld} belongs to a DIFFERENT attempt, which may be mid-charge. Handing that
     * one back would let a second charge start behind the first — so this must stay refused however
     * many times it is asked.
     */
    @Test
    void aGuardHeldByAnotherAttemptIsNeverHandedBack() {
        InitiatePaymentCommand command = commandFor("booking-held-elsewhere");
        when(sagaSteps.tryInitiate(any(), any(), any(), any()))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-3"));

        assertThat(attempt().execute(command)).isEqualTo("payment-3");

        // Two more pods, neither of which can see the row yet — both must be refused, and the
        // second proves the first did not clear a guard it never owned.
        assertThatThrownBy(() -> attempt().execute(command)).isInstanceOf(DuplicatePaymentException.class);
        assertThatThrownBy(() -> attempt().execute(command)).isInstanceOf(DuplicatePaymentException.class);

        verify(sagaSteps, times(1)).tryInitiate(any(), any(), any(), any());
    }

    /**
     * Cross-service coupling, pinned here because nothing else states it in one place:
     * booking-service decides whether to cancel a booking or let its payment run by matching this
     * exact wording out of a 409/422 body — see {@code BookingOrchestrationService
     * #isPaymentAlreadyBeingProcessed}, which lowercases the response and looks for
     * "already being processed". Reword this message and that predicate silently stops firing.
     *
     * <p>It is not firing today either, and this javadoc used to say the 422 was what reaches it.
     * {@code PaymentController#initiate} catches this exception and answers 202 Accepted, so the
     * message never becomes an HTTP body at all — booking-service's Step 4 sees a success and
     * falls through. The wording still has to be pinned, because the predicate is the net for the
     * day that catch is removed, and a reworded message would make it a silent no-op.
     */
    @Test
    void theRefusalCarriesTheWordingBookingServiceMatchesOn() {
        InitiatePaymentCommand command = commandFor("booking-wording");
        when(sagaSteps.tryInitiate(any(), any(), any(), any()))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-4"));

        attempt().execute(command);

        assertThatThrownBy(() -> attempt().execute(command))
                .isInstanceOf(DuplicatePaymentException.class)
                .satisfies(e -> assertThat(e.getMessage().toLowerCase(Locale.ROOT))
                        .contains("already being processed"));
    }
}
