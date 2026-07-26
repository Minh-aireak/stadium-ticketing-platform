package com.aireak.inventory.application.port.out;

import java.util.List;

/** Outbound port: domain event publishing to Kafka. */
public interface DomainEventPublisher {
    void publishAll(List<Object> domainEvents);
}
