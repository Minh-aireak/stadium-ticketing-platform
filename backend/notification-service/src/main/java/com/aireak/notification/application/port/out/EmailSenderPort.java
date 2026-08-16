package com.aireak.notification.application.port.out;

import com.aireak.notification.application.port.out.dto.EmailMessage;
import com.aireak.notification.domain.exception.EmailDeliveryException;

/**
 * Outbound port: sends one transactional email.
 *
 * <p>Implementations throw {@link EmailDeliveryException} on any delivery failure — they never
 * swallow it, so the caller decides whether a failed email should abort anything. The only
 * production caller,
 * {@link com.aireak.notification.application.service.TransactionalEmailService#sendEmail}, catches
 * it and logs rather than propagating.
 *
 * @see com.aireak.notification.adapter.out.email.BrevoEmailSenderAdapter
 */
public interface EmailSenderPort {

    void send(EmailMessage message);
}
