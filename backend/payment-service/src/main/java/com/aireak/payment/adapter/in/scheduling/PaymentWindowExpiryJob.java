package com.aireak.payment.adapter.in.scheduling;

import com.aireak.payment.application.port.in.ExpireCardPaymentsUseCase;
import com.aireak.payment.config.PaymentModeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Card mode's clock. A payment whose intent the customer never confirmed sits INITIATED with its
 * booking PENDING_PAYMENT and its seats reserved; nothing else in the system moves it, because
 * every other path (webhook, sync) waits for the customer to act. This job closes the window:
 * intents older than {@code payment.card.window-minutes} are cancelled at the gateway and the
 * payment marked FAILED, which is the same {@code PaymentFailedEvent} a declined auto-mode charge
 * raises -- booking-service cancels the booking and releases the seats without knowing why.
 *
 * <p>The window must close before ticket-inventory's reservation TTL (10 minutes by default) does,
 * or a late success would pay for seats the booking can no longer confirm -- see
 * {@code payment.card.window-minutes} in application.yaml. Auto mode has no such rows (no
 * clientSecret) and the job is not even registered there.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.mode", havingValue = "card")
public class PaymentWindowExpiryJob {

    private final ExpireCardPaymentsUseCase expireCardPaymentsUseCase;
    private final PaymentModeProperties paymentMode;
    // Counted, not gauged: each expiry is a customer who reached the form and did not finish.
    private final Counter expiredCounter;

    public PaymentWindowExpiryJob(ExpireCardPaymentsUseCase expireCardPaymentsUseCase,
                                  PaymentModeProperties paymentMode, MeterRegistry meterRegistry) {
        this.expireCardPaymentsUseCase = expireCardPaymentsUseCase;
        this.paymentMode = paymentMode;
        this.expiredCounter = meterRegistry.counter("payment.window.expired");
    }

    @Scheduled(fixedDelayString = "${payment.card.expiry-job.fixed-delay-ms:30000}")
    @SchedulerLock(name = "payment-windowExpiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void closeExpiredWindows() {
        Instant cutoff = Instant.now().minus(paymentMode.window());
        int closed = expireCardPaymentsUseCase.closeExpiredWindows(cutoff, paymentMode.card().expiryJob().batchSize());
        if (closed > 0) {
            expiredCounter.increment(closed);
            log.info("Expired {} card payment(s) whose window closed before {}", closed, cutoff);
        }
    }
}
