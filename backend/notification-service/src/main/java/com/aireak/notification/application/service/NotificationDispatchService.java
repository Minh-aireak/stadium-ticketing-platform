package com.aireak.notification.application.service;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import com.aireak.notification.application.service.TransactionalEmailService.EmailContent;
import com.aireak.notification.application.service.TransactionalEmailService.PasswordResetEmail;
import com.aireak.notification.application.service.TransactionalEmailService.PaymentSuccessEmail;
import com.aireak.notification.application.service.TransactionalEmailService.RefundIssuedEmail;
import com.aireak.notification.application.service.TransactionalEmailService.WelcomeEmail;
import com.aireak.notification.config.AppLinkProperties;
import com.aireak.notification.domain.model.Notification;
import com.aireak.payment.domain.event.PaymentRefundedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService implements SendNotificationUseCase {

    private final TransactionalEmailService transactionalEmailService;
    private final NotificationRecordingSteps recordingSteps;
    private final AppLinkProperties links;

    /**
     * Deliberately NOT {@code @Transactional}: {@link #dispatch} performs an outbound HTTP call to
     * the email provider, which must not run with a database connection held open — see
     * {@link NotificationRecordingSteps}. Only the persistent outcome is transactional, and it
     * commits in one short {@code REQUIRES_NEW} step once the send has returned.
     */
    @Override
    public void send(String eventId, String eventType, Object payload) {
        // Idempotency check — application layer, before any domain/infra call
        if (recordingSteps.alreadyProcessed(eventId)) {
            log.info("Event {} already processed — skipping", eventId);
            return;
        }

        // Dispatch-then-mark is intentional (kept as-is — see review 2026-07-29): if the process
        // crashes between dispatch() returning and the outcome committing, Kafka redelivers the
        // event and it goes out a second time. That is a known, accepted risk — Priority: Low —
        // because for a ticketing platform a duplicate confirmation email is far cheaper than a
        // silently dropped one (mark-then-dispatch would trade this for exactly that risk instead).
        Optional<Notification> inAppRecord = dispatch(eventType, payload);

        recordingSteps.recordDispatched(eventId, eventType, inAppRecord);
        log.info("Notification dispatched: eventId={}, eventType={}", eventId, eventType);
    }

    /**
     * Sends the email for this event and returns the in-app notification to persist, if the event
     * warrants one. Returns rather than saving, so the only writer stays
     * {@link NotificationRecordingSteps} and this method can run outside a transaction.
     */
    private Optional<Notification> dispatch(String eventType, Object payload) {
        return switch (eventType) {
            case KafkaTopics.ACCOUNT_REGISTERED -> welcome(payload);
            case KafkaTopics.PAYMENT_SUCCEEDED -> paymentSuccess(payload);
            case KafkaTopics.PAYMENT_REFUNDED -> refundIssued(payload);
            case KafkaTopics.PASSWORD_RESET_REQUESTED -> passwordReset(payload);
            case KafkaTopics.ACCOUNT_ACTIVATED -> accountActivated(payload);
            case KafkaTopics.BOOKING_CONFIRMED -> bookingConfirmed(payload);
            case KafkaTopics.BOOKING_CANCELLED -> bookingCancelled(payload);
            // Unknown event type — log and skip rather than crashing the consumer
            default -> {
                log.warn("No template for eventType={} — skipping", eventType);
                yield Optional.empty();
            }
        };
    }

    private Optional<Notification> welcome(Object payload) {
        AccountRegisteredEvent event = (AccountRegisteredEvent) payload;
        String verificationUrl = links.identityServiceBaseUrl()
                + "/api/v1/auth/verify-email?token=" + event.verificationToken();
        Optional<EmailContent> content = transactionalEmailService.sendWelcomeEmail(
                new WelcomeEmail(event.email().value(), event.accountId().value(), verificationUrl));
        return inAppRecord(event.accountId().value(), content);
    }

    private Optional<Notification> paymentSuccess(Object payload) {
        PaymentSucceededEvent event = (PaymentSucceededEvent) payload;
        if (event.customerEmail() == null || event.customerEmail().isBlank()) {
            // Payments initiated by an internal-service token (reconciliation, saga replay) carry
            // no end-user identity, so there is no address to notify — not an error.
            log.warn("PaymentSucceededEvent has no customerEmail — no receipt email sent: paymentId={}, bookingId={}",
                    event.paymentId(), event.bookingId());
            return Optional.empty();
        }
        // bookingId, not paymentId, is the "order code" the customer sees everywhere else in the
        // product (it's what the booking screens and the confirmation email already show).
        transactionalEmailService.sendPaymentSuccessEmail(new PaymentSuccessEmail(
                event.customerEmail(), event.bookingId(), event.amount(), event.currency(),
                event.occurredAt()));
        return Optional.empty();
    }

    /**
     * The cancellation email says the booking is gone; this one says the money is on its way back,
     * and until it existed the second half was never sent — payment-service published
     * {@code payment.payment.refunded} to nobody at all.
     *
     * <p>No in-app record, for the same reason {@link #paymentSuccess} raises none: the booking
     * event that preceded this one already wrote one, and two rows for one cancellation reads as a
     * duplicate rather than as detail.
     */
    private Optional<Notification> refundIssued(Object payload) {
        PaymentRefundedEvent event = (PaymentRefundedEvent) payload;
        if (event.customerEmail() == null || event.customerEmail().isBlank()) {
            // Same case as paymentSuccess: a payment initiated with an internal-service token has
            // no end-user identity, so there is no address to notify — not an error.
            log.warn("PaymentRefundedEvent has no customerEmail — no refund email sent: paymentId={}, bookingId={}",
                    event.paymentId(), event.bookingId());
            return Optional.empty();
        }
        // bookingId as the order code, matching the receipt email and the booking screens.
        transactionalEmailService.sendRefundIssuedEmail(new RefundIssuedEmail(
                event.customerEmail(), event.bookingId(), event.amount(), event.currency(),
                event.reason(), event.occurredAt()));
        return Optional.empty();
    }

    /**
     * No in-app notification record for this one: a reset link is a live credential, and the
     * in-app feed outlives the token's short TTL and is readable by anyone already holding a
     * session for the account.
     */
    private Optional<Notification> passwordReset(Object payload) {
        PasswordResetRequestedEvent event = (PasswordResetRequestedEvent) payload;
        String resetUrl = links.frontendBaseUrl() + "/reset-password?token=" + event.resetToken();
        transactionalEmailService.sendPasswordResetEmail(
                new PasswordResetEmail(event.email().value(), resetUrl, event.expiresInMinutes()));
        return Optional.empty();
    }

    private Optional<Notification> accountActivated(Object payload) {
        AccountActivatedEvent event = (AccountActivatedEvent) payload;
        Map<String, Object> model = new HashMap<>();
        model.put("accountId", event.accountId().value());
        model.put("email", event.email().value());
        Optional<EmailContent> content = transactionalEmailService.sendTemplatedEmail(
                "account-activated", "Your account is now active", event.email().value(), model);
        return inAppRecord(event.accountId().value(), content);
    }

    private Optional<Notification> bookingConfirmed(Object payload) {
        BookingConfirmedEvent event = (BookingConfirmedEvent) payload;
        Map<String, Object> model = new HashMap<>();
        // customerId is not in the model: it addresses the in-app record below, and the template
        // never showed a customer their own id. Same for occurredAt, which no template renders --
        // see noTemplateRendersTheCustomerIdOrOccurredAtThatUsedToBePutIntoItsModel.
        model.put("bookingId", event.bookingId());
        model.put("showtimeId", event.showtimeId());
        model.put("seatCodes", event.seatCodes());
        model.put("amount", event.amount().amount());
        model.put("currency", event.amount().currency());
        Optional<EmailContent> content = transactionalEmailService.sendTemplatedEmail(
                "booking-confirmed", "Your booking is confirmed!", event.customerEmail(), model);
        return inAppRecord(event.customerId(), content);
    }

    private Optional<Notification> bookingCancelled(Object payload) {
        BookingCancelledEvent event = (BookingCancelledEvent) payload;
        Map<String, Object> model = new HashMap<>();
        model.put("bookingId", event.bookingId());
        model.put("showtimeId", event.showtimeId());
        model.put("reason", event.reason());
        Optional<EmailContent> content = transactionalEmailService.sendTemplatedEmail(
                "booking-cancelled", "Your booking has been cancelled", event.customerEmail(), model);
        return inAppRecord(event.customerId(), content);
    }

    /**
     * In-app record for GET /api/v1/notifications — separate from the email side effect above;
     * reuses the already-rendered plain-text body instead of a second template pass.
     */
    private Optional<Notification> inAppRecord(String recipientId, Optional<EmailContent> content) {
        return content.map(c -> Notification.create(recipientId, c.subject(), c.textBody()));
    }
}
