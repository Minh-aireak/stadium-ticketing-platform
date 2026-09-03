package com.aireak.payment.adapter.in.scheduling;

import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.domain.model.UnreconciledPayment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnreconciledPaymentAlertJobTest {

    @Mock
    private PaymentReconciliationPort paymentReconciliationPort;

    private MeterRegistry meterRegistry;
    private UnreconciledPaymentAlertJob alertJob;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        alertJob = new UnreconciledPaymentAlertJob(paymentReconciliationPort, meterRegistry);
        setField(alertJob, "graceMinutes", 5L);
        setField(alertJob, "batchSize", 100);
    }

    private double gaugeValue() {
        return meterRegistry.get("payment.unreconciled.count").gauge().value();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void scanAndAlert_whenNoUnresolved_doesNothing() {
        when(paymentReconciliationPort.findUnresolvedOlderThan(any(), anyInt()))
                .thenReturn(Collections.emptyList());

        alertJob.scanAndAlert();

        verify(paymentReconciliationPort).findUnresolvedOlderThan(any(Instant.class), anyInt());
    }

    @Test
    void scanAndAlert_whenUnresolvedExist_logsAlertsAndPassesCorrectCutoff() {
        UnreconciledPayment item = new UnreconciledPayment(
                UUID.randomUUID(),
                "pay_123",
                "book_456",
                "ch_789",
                new BigDecimal("150.00"),
                "USD",
                "DB connection failed",
                false,
                "CHARGE",
                Instant.now().minus(10, ChronoUnit.MINUTES)
        );

        when(paymentReconciliationPort.findUnresolvedOlderThan(any(), anyInt()))
                .thenReturn(List.of(item));

        alertJob.scanAndAlert();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(paymentReconciliationPort).findUnresolvedOlderThan(cutoffCaptor.capture(), limitCaptor.capture());

        Instant expectedCutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
        assertThat(limitCaptor.getValue()).isEqualTo(100);
    }

    /**
     * The gauge is what the UnreconciledPaymentsAccumulating alert reads — a log line alone only
     * reaches someone already tailing logs.
     */
    @Test
    void scanAndAlert_publishesTheUnresolvedCountAsAGauge() {
        when(paymentReconciliationPort.findUnresolvedOlderThan(any(), anyInt()))
                .thenReturn(List.of(unreconciled("pay_1"), unreconciled("pay_2")));

        alertJob.scanAndAlert();

        assertThat(gaugeValue()).isEqualTo(2.0);
    }

    /** A resolved backlog must clear the gauge, otherwise the alert would latch on forever. */
    @Test
    void scanAndAlert_resetsTheGaugeOnceNothingIsUnresolved() {
        when(paymentReconciliationPort.findUnresolvedOlderThan(any(), anyInt()))
                .thenReturn(List.of(unreconciled("pay_1")))
                .thenReturn(Collections.emptyList());

        alertJob.scanAndAlert();
        assertThat(gaugeValue()).isEqualTo(1.0);

        alertJob.scanAndAlert();
        assertThat(gaugeValue()).isZero();
    }

    private UnreconciledPayment unreconciled(String paymentId) {
        return new UnreconciledPayment(
                UUID.randomUUID(), paymentId, "book_456", "ch_789",
                new BigDecimal("150.00"), "USD", "DB connection failed", false, "CHARGE",
                Instant.now().minus(10, ChronoUnit.MINUTES));
    }
}
