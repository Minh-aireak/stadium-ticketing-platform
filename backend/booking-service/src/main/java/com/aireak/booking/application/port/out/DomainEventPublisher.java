package com.aireak.booking.application.port.out;

import java.util.List;

// Outbound port: Kafka event publishing for booking events.
public interface DomainEventPublisher {
    void publishAll(List<Object> events);
}
