package com.aireak.notification.application.service;

import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.application.port.out.ProcessedEventRepository;
import com.aireak.notification.domain.model.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The persistent half of handling one notification event, in its own short transaction.
 *
 * <p>Own bean because {@code @Transactional} is proxy-based — a self-invoked method on
 * {@link NotificationDispatchService} would silently run without one. Same reason
 * {@code BookingSagaSteps} and {@code PaymentSagaSteps} exist in their services.
 *
 * <p>The point of the split is what is <em>not</em> in here: the email send. It is an HTTP call to
 * Brevo, and it used to sit inside this transaction, holding a Hikari connection for the whole
 * round trip. Under a slow or unresponsive provider the Kafka listener threads would each park on
 * a connection until the pool was empty — the exact failure payment-service and booking-service
 * already design against ("the gateway REST call in between never holds a Hikari connection open
 * while waiting on network I/O", {@code PaymentService}).
 */
@Component
@RequiredArgsConstructor
class NotificationRecordingSteps {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;

    boolean alreadyProcessed(String eventId) {
        return processedEventRepository.existsByEventId(eventId);
    }

    /**
     * Records the in-app notification (when the dispatch produced one) and marks the event
     * processed, atomically — the two must not be able to disagree about whether this event was
     * handled.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordDispatched(String eventId, String eventType, Optional<Notification> notification) {
        notification.ifPresent(notificationRepository::save);
        processedEventRepository.markProcessed(eventId, eventType);
    }
}
