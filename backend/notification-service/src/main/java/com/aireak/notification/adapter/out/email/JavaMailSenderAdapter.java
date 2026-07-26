package com.aireak.notification.adapter.out.email;

import com.aireak.notification.application.port.out.EmailSenderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Outbound email adapter using Spring's {@link JavaMailSender}.
 * Implements the {@link EmailSenderPort} driven port.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavaMailSenderAdapter implements EmailSenderPort {

    private final JavaMailSender mailSender;

    @Override
    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to={}", to);
        } catch (Exception ex) {
            log.error("Failed to send email to={}: {}", to, ex.getMessage(), ex);
            throw ex; // re-throw so Kafka consumer can handle retry/DLQ
        }
    }
}
