package com.aireak.notification.application.service;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.ProcessedEventRepository;
import com.aireak.notification.application.port.out.SmsSenderPort;
import com.aireak.notification.domain.model.NotificationChannel;
import com.aireak.notification.domain.model.NotificationTemplate;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Application service implementing the notification dispatch use case.
 *
 * <p><strong>Idempotency</strong>: checks {@link ProcessedEventRepository} before
 * processing. If the eventId has already been processed, the method returns immediately.
 * The {@code markProcessed} call and the actual send are wrapped in one transaction
 * to guarantee at-most-once delivery semantics (with the idempotency store).
 *
 * <p>Templates are resolved from static registry and rendered dynamically using FreeMarker.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService implements SendNotificationUseCase {

    private final EmailSenderPort emailSenderPort;
    private final SmsSenderPort smsSenderPort;
    private final ProcessedEventRepository processedEventRepository;
    private final Configuration freemarkerConfig;

    /**
     * Static template registry.
     * Key: eventType (matches KafkaTopics constants)
     */
    private static final Map<String, NotificationTemplate> TEMPLATES = Map.of(
            "booking.booking.confirmed",
            new NotificationTemplate("booking.booking.confirmed", NotificationChannel.EMAIL,
                    "Your booking is confirmed!", "booking-confirmed"),

            "booking.booking.cancelled",
            new NotificationTemplate("booking.booking.cancelled", NotificationChannel.EMAIL,
                    "Your booking has been cancelled", "booking-cancelled"),

            "identity.account.registered",
            new NotificationTemplate("identity.account.registered", NotificationChannel.EMAIL,
                    "Welcome! Please verify your email", "account-welcome")
    );

    @Override
    @Transactional
    public void send(String eventId, String eventType, Object payload) {
        // Idempotency check — application layer, before any domain/infra call
        if (processedEventRepository.existsByEventId(eventId)) {
            log.info("Event {} already processed — skipping", eventId);
            return;
        }

        NotificationTemplate template = TEMPLATES.get(eventType);
        if (template == null) {
            // Unknown event type — log and skip rather than crashing the consumer
            log.warn("No template for eventType={}, eventId={} — skipping", eventType, eventId);
            processedEventRepository.markProcessed(eventId, eventType);
            return;
        }

        dispatch(template, payload);

        // Mark processed only after successful dispatch (transactional boundary)
        processedEventRepository.markProcessed(eventId, eventType);
        log.info("Notification dispatched: eventId={}, eventType={}, channel={}",
                eventId, eventType, template.getChannel());
    }

    private void dispatch(NotificationTemplate template, Object payload) {
        String email = extractEmail(payload);
        String phone = extractPhone(payload);
        String renderedBody = renderTemplate(template.getBodyTemplate(), payload);

        switch (template.getChannel()) {
            case EMAIL -> emailSenderPort.send(
                    email,
                    template.getSubjectTemplate(),
                    renderedBody
            );
            case SMS -> smsSenderPort.send(
                    phone,
                    template.getSubjectTemplate() + " - " + renderedBody
            );
            default -> log.warn("Unhandled channel: {}", template.getChannel());
        }
    }

    private String renderTemplate(String templateName, Object payload) {
        try {
            Template fmTemplate = freemarkerConfig.getTemplate(templateName + ".ftl");
            Map<String, Object> model = buildModel(payload);
            StringWriter writer = new StringWriter();
            fmTemplate.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("Failed to render FreeMarker template: {}", templateName, e);
            return "Failed to render notification. Payload: " + (payload != null ? payload.toString() : "null");
        }
    }

    private Map<String, Object> buildModel(Object payload) {
        Map<String, Object> model = new HashMap<>();
        if (payload instanceof BookingConfirmedEvent event) {
            model.put("bookingId", event.bookingId());
            model.put("customerId", event.customerId());
            model.put("showtimeId", event.showtimeId());
            model.put("seatCodes", event.seatCodes());
            model.put("amount", event.amount().amount());
            model.put("currency", event.amount().currency());
            model.put("occurredAt", event.occurredAt());
        } else if (payload instanceof BookingCancelledEvent event) {
            model.put("bookingId", event.bookingId());
            model.put("customerId", event.customerId());
            model.put("showtimeId", event.showtimeId());
            model.put("reason", event.reason());
            model.put("occurredAt", event.occurredAt());
        } else if (payload instanceof AccountRegisteredEvent event) {
            model.put("accountId", event.accountId().value());
            model.put("email", event.email().value());
            model.put("occurredAt", event.occurredAt());
        } else if (payload instanceof Map<?, ?> map) {
            // Fallback for map type payloads
            map.forEach((k, v) -> model.put(k.toString(), v));
        }
        return model;
    }

    private String extractEmail(Object payload) {
        if (payload instanceof AccountRegisteredEvent event) {
            return event.email().value();
        } else if (payload instanceof BookingConfirmedEvent event) {
            return "customer-" + event.customerId() + "@example.com";
        } else if (payload instanceof BookingCancelledEvent event) {
            return "customer-" + event.customerId() + "@example.com";
        }
        return "user@example.com";
    }

    private String extractPhone(Object payload) {
        return "+84000000000";
    }
}
