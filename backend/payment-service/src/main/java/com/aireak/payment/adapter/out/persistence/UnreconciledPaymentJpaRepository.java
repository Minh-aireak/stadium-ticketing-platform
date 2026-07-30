package com.aireak.payment.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface UnreconciledPaymentJpaRepository extends JpaRepository<UnreconciledPaymentJpaEntity, UUID> {
    List<UnreconciledPaymentJpaEntity> findByResolvedFalseAndCreatedAtBefore(Instant cutoff, Pageable pageable);
}

