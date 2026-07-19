package com.aireak.catalog.application.port.out;

import java.util.List;

public interface DomainEventPublisher {
    void publishAll(List<Object> events);
}
