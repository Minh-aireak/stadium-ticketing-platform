package com.aireak.notification.domain.model;

import lombok.Getter;

import java.util.Objects;

/**
 * Lightweight domain concept: maps an event type to a template and channel.
 * No persistence here — templates are loaded from config or a simple DB table.
 *
 * <p>Domain rule: each eventType has exactly one primary template+channel mapping.
 */
@Getter
public class NotificationTemplate {

    private final String eventType;        // e.g. "booking.booking.confirmed"
    private final NotificationChannel channel;
    private final String subjectTemplate;  // e.g. "Booking #{bookingId} confirmed"
    private final String bodyTemplate;     // Mustache/Freemarker template name

    public NotificationTemplate(String eventType,
                                NotificationChannel channel,
                                String subjectTemplate,
                                String bodyTemplate) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(bodyTemplate, "bodyTemplate must not be null");

        this.eventType = eventType;
        this.channel = channel;
        this.subjectTemplate = subjectTemplate;
        this.bodyTemplate = bodyTemplate;
    }
}
