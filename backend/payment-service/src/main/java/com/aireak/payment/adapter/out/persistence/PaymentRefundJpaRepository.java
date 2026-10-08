package com.aireak.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRefundJpaRepository extends JpaRepository<PaymentRefundJpaEntity, String> {
}
