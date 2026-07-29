package com.aireak.payment.adapter.out.persistence;

import com.aireak.payment.application.port.out.ProcessedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing the {@link ProcessedWebhookEventRepository} port.
 * Hexagonal rule: the application service depends only on the port interface.
 */
@Component
@RequiredArgsConstructor
public class ProcessedWebhookEventPersistenceAdapter implements ProcessedWebhookEventRepository {

    private final ProcessedWebhookEventJpaRepository jpaRepository;

    @Override
    public boolean existsByEventId(String eventId) {
        return jpaRepository.existsById(eventId);
    }

    @Override
    public void markProcessed(String eventId, String eventType) {
        if (!jpaRepository.existsById(eventId)) {
            jpaRepository.save(ProcessedWebhookEventJpaEntity.of(eventId, eventType));
        }
    }
}
