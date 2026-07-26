package com.aireak.notification.application.port.out;

/**
 * Outbound port: sends email notifications.
 * Implemented by JavaMailSenderAdapter in adapter/out/email.
 */
public interface EmailSenderPort {

    /**
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    plain-text or HTML body
     */
    void send(String to, String subject, String body);
}
