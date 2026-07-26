package com.aireak.notification.domain.exception;

import com.aireak.common.exception.DomainException;

/**
 * Thrown when no NotificationTemplate is found for a given event type.
 */
public class TemplateNotFoundException extends DomainException {

    public TemplateNotFoundException(String eventType) {
        super("No notification template found for event type: " + eventType);
    }
}
