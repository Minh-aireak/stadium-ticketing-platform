package com.aireak.notification.adapter.out.sms;

import com.aireak.notification.application.port.out.SmsSenderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub SMS adapter — logs the message instead of actually sending.
 * Replace with Twilio/ViettelSMS/etc. adapter when ready.
 *
 * <p>Hexagonal rule: only this class changes when the SMS provider changes.
 * The port interface and application layer remain untouched.
 */
@Slf4j
@Component
public class StubSmsAdapter implements SmsSenderPort {

    @Override
    public void send(String phoneNumber, String message) {
        // STUB — replace with actual SMS gateway SDK call
        log.info("[STUB SMS] To={} | Message={}", phoneNumber, message);
    }
}
