package com.aireak.notification.adapter.out.email;

import com.aireak.notification.application.port.out.EmailSenderPort;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Outbound email adapter using Spring's {@link JavaMailSender}.
 * Implements the {@link EmailSenderPort} driven port.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavaMailSenderAdapter implements EmailSenderPort {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Override
    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setSubject(subject);
            if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress);
            }
            helper.setText(body, true);
            message.saveChanges();
            mailSender.send(message);
            log.info("Email sent to={}", to);
        } catch (Exception ex) {
            log.error("Failed to send email to={}: {}", to, ex.getMessage(), ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to send email to=" + to, ex);
        }
    }
}
