package com.aireak.notification.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationJpaRepository extends JpaRepository<NotificationJpaEntity, String> {
    Page<NotificationJpaEntity> findByRecipientId(String recipientId, Pageable pageable);
    long countByRecipientId(String recipientId);
}
