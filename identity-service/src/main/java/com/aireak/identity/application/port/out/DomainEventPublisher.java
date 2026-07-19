package com.aireak.identity.application.port.out;

/**
 * Outbound port: domain event publishing (to Kafka).
 * Implemented by AccountEventPublisher in adapter/out/messaging.
 *
 * <p>Takes raw domain event objects — the adapter wraps them in EventEnvelope.
 */
public interface DomainEventPublisher {
    void publish(Object domainEvent);
    void publishAll(java.util.List<Object> domainEvents);
}
