package com.aireak.notification.application.service;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.application.port.out.ProcessedEventRepository;
import com.aireak.notification.application.service.TransactionalEmailService.EmailContent;
import com.aireak.notification.application.service.TransactionalEmailService.PasswordResetEmail;
import com.aireak.notification.application.service.TransactionalEmailService.PaymentSuccessEmail;
import com.aireak.notification.application.service.TransactionalEmailService.WelcomeEmail;
import com.aireak.notification.config.AppLinkProperties;
import com.aireak.notification.domain.model.Notification;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService implements SendNotificationUseCase {

    private final TransactionalEmailService transactionalEmailService;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final AppLinkProperties links;

    @Override
    @Transactional
    public void send(String eventId, String eventType, Object payload) {
        // Idempotency check — application layer, before any domain/infra call
        if (processedEventRepository.existsByEventId(eventId)) {
            log.info("Event {} already processed — skipping", eventId);
            return;
        }

        // Dispatch-then-mark is intentional (kept as-is — see review 2026-07-29): if the process
        // crashes between dispatch() returning and markProcessed() committing, Kafka redelivers
        // the event and it goes out a second time. That is a known, accepted risk — Priority: Low —
        // because for a ticketing platform a duplicate confirmation email is far cheaper than a
        // silently dropped one (mark-then-dispatch would trade this for exactly that risk instead).
        dispatch(eventType, payload);

        // Mark processed only after successful dispatch (transactional boundary)
        processedEventRepository.markProcessed(eventId, eventType);
        log.info("Notification dispatched: eventId={}, eventType={}", eventId, eventType);
    }

    private void dispatch(String eventType, Object payload) {
        switch (eventType) {
            case KafkaTopics.ACCOUNT_REGISTERED -> welcome(payload);
            case KafkaTopics.PAYMENT_SUCCEEDED -> paymentSuccess(payload);
            case KafkaTopics.PASSWORD_RESET_REQUESTED -> passwordReset(payload);
            case KafkaTopics.ACCOUNT_ACTIVATED -> accountActivated(payload);
            case KafkaTopics.BOOKING_CONFIRMED -> bookingConfirmed(payload);
            case KafkaTopics.BOOKING_CANCELLED -> bookingCancelled(payload);
            // Unknown event type — log and skip rather than crashing the consumer
            default -> log.warn("No template for eventType={} — skipping", eventType);
        }
    }

    private void welcome(Object payload) {
        AccountRegisteredEvent event = (AccountRegisteredEvent) payload;
        String verificationUrl = links.identityServiceBaseUrl()
                + "/api/v1/auth/verify-email?token=" + event.verificationToken();
        Optional<EmailContent> content = transactionalEmailService.sendWelcomeEmail(
                new WelcomeEmail(event.email().value(), event.accountId().value(), verificationUrl));
        recordInApp(event.accountId().value(), content);
    }

    private void paymentSuccess(Object payload) {
        PaymentSucceededEvent event = (PaymentSucceededEvent) payload;
        if (event.customerEmail() == null || event.customerEmail().isBlank()) {
            // Payments initiated by an internal-service token (reconciliation, saga replay) carry
            // no end-user identity, so there is no address to notify — not an error.
            log.warn("PaymentSucceededEvent has no customerEmail — no receipt email sent: paymentId={}, bookingId={}",
                    event.paymentId(), event.bookingId());
            return;
        }
        // bookingId, not paymentId, is the "order code" the customer sees everywhere else in the
        // product (it's what the booking screens and the confirmation email already show).
        transactionalEmailService.sendPaymentSuccessEmail(new PaymentSuccessEmail(
                event.customerEmail(), event.bookingId(), event.amount(), event.currency(),
                event.occurredAt()));
    }

    /**
     * No in-app notification record for this one: a reset link is a live credential, and the
     * in-app feed outlives the token's short TTL and is readable by anyone already holding a
     * session for the account.
     */
    private void passwordReset(Object payload) {
        PasswordResetRequestedEvent event = (PasswordResetRequestedEvent) payload;
        String resetUrl = links.frontendBaseUrl() + "/reset-password?token=" + event.resetToken();
        transactionalEmailService.sendPasswordResetEmail(
                new PasswordResetEmail(event.email().value(), resetUrl, event.expiresInMinutes()));
    }

    private void accountActivated(Object payload) {
        AccountActivatedEvent event = (AccountActivatedEvent) payload;
        Map<String, Object> model = new HashMap<>();
        model.put("accountId", event.accountId().value());
        model.put("email", event.email().value());
        model.put("occurredAt", event.occurredAt());
        Optional<EmailContent> content = transactionalEmailService.sendTemplatedEmail(
                "account-activated", "Your account is now active", event.email().value(), model);
        recordInApp(event.accountId().value(), content);
    }

    private void bookingConfirmed(Object payload) {
        BookingConfirmedEvent event = (BookingConfirmedEvent) payload;
        Map<String, Object> model = new HashMap<>();
        model.put("bookingId", event.bookingId());
        model.put("customerId", event.customerId());
        model.put("showtimeId", event.showtimeId());
        model.put("seatCodes", event.seatCodes());
        model.put("amount", event.amount().amount());
        model.put("currency", event.amount().currency());
        model.put("occurredAt", event.occurredAt());
        Optional<EmailContent> content = transactionalEmailService.sendTemplatedEmail(
                "booking-confirmed", "Your booking is confirmed!", event.customerEmail(), model);
        recordInApp(event.customerId(), content);
    }

    private void bookingCancelled(Object payload) {
        BookingCancelledEvent event = (BookingCancelledEvent) payload;
        Map<String, Object> model = new HashMap<>();
        model.put("bookingId", event.bookingId());
        model.put("customerId", event.customerId());
        model.put("showtimeId", event.showtimeId());
        model.put("reason", event.reason());
        model.put("occurredAt", event.occurredAt());
        Optional<EmailContent> content = transactionalEmailService.sendTemplatedEmail(
                "booking-cancelled", "Your booking has been cancelled", event.customerEmail(), model);
        recordInApp(event.customerId(), content);
    }

    /**
     * In-app record for GET /api/v1/notifications — separate from the email side effect above;
     * reuses the already-rendered plain-text body instead of a second template pass.
     */
    private void recordInApp(String recipientId, Optional<EmailContent> content) {
        content.ifPresent(c ->
                notificationRepository.save(Notification.create(recipientId, c.subject(), c.textBody())));
    }
}
