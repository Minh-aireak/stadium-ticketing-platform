package com.aireak.payment.adapter.in.scheduling;

import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.domain.model.UnreconciledPayment;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Inbound scheduling adapter: scans {@code unreconciled_payments} table periodically for records
 * that remain unresolved past the configured grace period, logging clear WARN/ERROR alerts for manual
 * financial reconciliation as required by §4 item 6 of audit report.
 *
 * <p>Also publishes what it found as {@code payment.unreconciled.count}, because a log line only
 * alerts someone who is already reading logs. This is the money-losing failure of the whole
 * platform — a customer charged whose booking never confirmed — so it needs to be reachable from
 * Prometheus (see {@code infra/prometheus/rules/platform-alerts.yml}).
 */
@Slf4j
@Component
public class UnreconciledPaymentAlertJob {

    private final PaymentReconciliationPort paymentReconciliationPort;
    // Last observed count, held so the gauge has something to read between scans. Deliberately
    // reset to 0 on a clean scan, otherwise a resolved backlog would leave the alert firing.
    private final AtomicInteger unreconciledCount = new AtomicInteger();

    public UnreconciledPaymentAlertJob(PaymentReconciliationPort paymentReconciliationPort,
                                       MeterRegistry meterRegistry) {
        this.paymentReconciliationPort = paymentReconciliationPort;
        meterRegistry.gauge("payment.unreconciled.count", unreconciledCount);
    }

    @Value("${payment.unreconciled-alert.grace-minutes:5}")
    private long graceMinutes = 5;

    @Value("${payment.unreconciled-alert.batch-size:200}")
    private int batchSize = 200;

    @Scheduled(fixedDelayString = "${payment.unreconciled-alert.fixed-delay-ms:300000}")
    @SchedulerLock(name = "payment-unreconciledAlert", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scanAndAlert() {
        Instant cutoff = Instant.now().minus(graceMinutes, ChronoUnit.MINUTES);
        List<UnreconciledPayment> unresolvedList = paymentReconciliationPort.findUnresolvedOlderThan(cutoff, batchSize);
        unreconciledCount.set(unresolvedList.size());
        if (unresolvedList.isEmpty()) {
            return;
        }

        log.warn("Found {} unresolved payment(s) in unreconciled_payments older than {} minute(s) requiring manual intervention",
                unresolvedList.size(), graceMinutes);

        for (UnreconciledPayment record : unresolvedList) {
            log.error("UNRECONCILED PAYMENT ALERT: Payment ID '{}' (Booking ID '{}', Gateway Tx '{}') " +
                    "amount {} {} created at {} remains UNRESOLVED for more than {} minute(s). Reason: {}",
                    record.getPaymentId(),
                    record.getBookingId(),
                    record.getGatewayTransactionId(),
                    record.getAmount(),
                    record.getCurrency(),
                    record.getCreatedAt(),
                    graceMinutes,
                    record.getFailureReason() != null ? record.getFailureReason() : "N/A");
        }
    }
}
