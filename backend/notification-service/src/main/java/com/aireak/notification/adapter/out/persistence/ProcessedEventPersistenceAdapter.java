package com.aireak.notification.adapter.out.persistence;

import com.aireak.notification.application.port.out.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persistence adapter implementing the {@link ProcessedEventRepository} port.
 * Translates between the port interface and the JPA layer.
 *
 * <p>Hexagonal rule: the application service depends only on the port interface,
 * never on this class directly.
 */
@Component
@RequiredArgsConstructor
public class ProcessedEventPersistenceAdapter implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository jpaRepository;

    @Override
    public boolean existsByEventId(String eventId) {
        return jpaRepository.existsById(eventId);
    }

    @Override
    public void markProcessed(String eventId, String eventType) {
        if (!jpaRepository.existsById(eventId)) {
            jpaRepository.save(new ProcessedEventJpaEntity(eventId, eventType));
        }
    }
}
