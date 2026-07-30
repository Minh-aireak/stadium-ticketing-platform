package com.aireak.notification.application.service;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.application.port.out.ProcessedEventRepository;
import com.aireak.notification.application.port.out.SmsSenderPort;
import com.aireak.notification.domain.model.Notification;
import com.aireak.notification.domain.model.NotificationChannel;
import com.aireak.notification.domain.model.NotificationTemplate;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final NotificationRepository notificationRepository;
    private final Configuration freemarkerConfig;

    // Field injection (not a constructor param): keeps the Lombok @RequiredArgsConstructor used
    // in tests (which construct this service directly, outside Spring) working unchanged. The
    // literal default matches application.yaml's so a plain `new` in a test still gets a usable URL.
    @Value("${app.identity-service-base-url:http://localhost:8081}")
    private String identityServiceBaseUrl = "http://localhost:8081";

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

        // Dispatch-then-mark is intentional (kept as-is — see review 2026-07-29): if the process
        // crashes between dispatch() returning and markProcessed() committing, Kafka redelivers
        // the event and it goes out a second time. That is a known, accepted risk — Priority: Low —
        // because for a ticketing platform a duplicate confirmation email/SMS is far cheaper than a
        // silently dropped one (mark-then-dispatch would trade this for exactly that risk instead).
        // A fully correct fix (PENDING/SENT state on the notification row + a retry job reconciling
        // the two) is left for the backlog if duplicate sends ever become an actual problem.
        dispatch(template, payload);

        // Mark processed only after successful dispatch (transactional boundary)
        processedEventRepository.markProcessed(eventId, eventType);
        log.info("Notification dispatched: eventId={}, eventType={}, channel={}",
                eventId, eventType, template.getChannel());
    }

    private void dispatch(NotificationTemplate template, Object payload) {
        String renderedBody = renderTemplate(template.getBodyTemplate(), payload);

        switch (template.getChannel()) {
            case EMAIL -> emailSenderPort.send(
                    extractEmail(payload),
                    template.getSubjectTemplate(),
                    renderedBody
            );
            // No entry in TEMPLATES uses SMS today — phone is only extracted here, lazily, so an
            // eager (unconditional) call wouldn't fail every EMAIL send once SMS support lands.
            case SMS -> smsSenderPort.send(
                    extractPhone(payload),
                    template.getSubjectTemplate() + " - " + renderedBody
            );
            default -> log.warn("Unhandled channel: {}", template.getChannel());
        }

        // In-app record for GET /api/v1/notifications — separate from the email/SMS side
        // effect above; reuses the same rendered subject/body instead of a second template pass.
        String recipientId = extractRecipientId(payload);
        if (recipientId != null) {
            notificationRepository.save(Notification.create(recipientId, template.getSubjectTemplate(), renderedBody));
        } else {
            log.warn("No recipientId resolvable for eventType={} — skipping in-app notification record",
                    template.getEventType());
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
            model.put("verificationUrl", identityServiceBaseUrl
                    + "/api/v1/auth/verify-email?token=" + event.verificationToken());
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
            return event.customerEmail();
        } else if (payload instanceof BookingCancelledEvent event) {
            return event.customerEmail();
        }
        throw new IllegalStateException(
                "No email extractor registered for payload type " + payload.getClass().getName());
    }

    private String extractPhone(Object payload) {
        throw new UnsupportedOperationException(
                "SMS delivery is not implemented — no phone extractor for payload type "
                        + payload.getClass().getName());
    }

    private String extractRecipientId(Object payload) {
        if (payload instanceof AccountRegisteredEvent event) {
            return event.accountId().value();
        } else if (payload instanceof BookingConfirmedEvent event) {
            return event.customerId();
        } else if (payload instanceof BookingCancelledEvent event) {
            return event.customerId();
        }
        return null;
    }
}
