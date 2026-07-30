package com.aireak.payment.adapter.out.persistence;

import com.aireak.payment.domain.model.UnreconciledPayment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnreconciledPaymentPersistenceAdapterTest {

    @Mock
    private UnreconciledPaymentJpaRepository jpaRepository;

    @InjectMocks
    private UnreconciledPaymentPersistenceAdapter adapter;

    @Test
    void recordUnpersistedSuccess_savesJpaEntity() {
        adapter.recordUnpersistedSuccess(
                "pay_1", "book_1", "pi_1",
                new BigDecimal("99.99"), "USD", "DB timeout"
        );

        ArgumentCaptor<UnreconciledPaymentJpaEntity> captor = ArgumentCaptor.forClass(UnreconciledPaymentJpaEntity.class);
        verify(jpaRepository).save(captor.capture());

        UnreconciledPaymentJpaEntity saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo("pay_1");
        assertThat(saved.getBookingId()).isEqualTo("book_1");
        assertThat(saved.getGatewayTransactionId()).isEqualTo("pi_1");
        assertThat(saved.getAmount()).isEqualByComparingTo("99.99");
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getFailureReason()).isEqualTo("DB timeout");
        assertThat(saved.isResolved()).isFalse();
    }

    @Test
    void findUnresolvedOlderThan_queriesRepositoryAndMapsToDomain() {
        Instant cutoff = Instant.now();
        UnreconciledPaymentJpaEntity entity = new UnreconciledPaymentJpaEntity(
                UUID.randomUUID(), "pay_1", "book_1", "pi_1",
                new BigDecimal("50.00"), "USD", "Connection drop", false, cutoff.minusSeconds(300)
        );

        when(jpaRepository.findByResolvedFalseAndCreatedAtBefore(eq(cutoff), eq(PageRequest.of(0, 10))))
                .thenReturn(List.of(entity));

        List<UnreconciledPayment> result = adapter.findUnresolvedOlderThan(cutoff, 10);

        assertThat(result).hasSize(1);
        UnreconciledPayment domain = result.get(0);
        assertThat(domain.getId()).isEqualTo(entity.getId());
        assertThat(domain.getPaymentId()).isEqualTo("pay_1");
        assertThat(domain.getBookingId()).isEqualTo("book_1");
        assertThat(domain.getGatewayTransactionId()).isEqualTo("pi_1");
        assertThat(domain.getAmount()).isEqualByComparingTo("50.00");
        assertThat(domain.getCurrency()).isEqualTo("USD");
        assertThat(domain.getFailureReason()).isEqualTo("Connection drop");
        assertThat(domain.isResolved()).isFalse();
    }
}
