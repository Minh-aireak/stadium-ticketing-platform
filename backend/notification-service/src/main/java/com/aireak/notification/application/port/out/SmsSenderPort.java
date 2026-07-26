package com.aireak.notification.application.port.out;

/**
 * Outbound port: sends SMS notifications.
 * Implemented by StubSmsAdapter (real integration deferred).
 */
public interface SmsSenderPort {

    /**
     * @param phoneNumber recipient phone number (E.164 format, e.g. "+84901234567")
     * @param message     SMS body (max 160 chars for single SMS)
     */
    void send(String phoneNumber, String message);
}
