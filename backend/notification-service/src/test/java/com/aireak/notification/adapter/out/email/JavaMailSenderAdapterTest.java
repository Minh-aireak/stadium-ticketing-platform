package com.aireak.notification.adapter.out.email;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaMailSenderAdapterTest {

    @Mock
    private JavaMailSender mailSender;

    private JavaMailSenderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JavaMailSenderAdapter(mailSender);
    }

    @Test
    void send_createsMultipartHtmlMessageWithCorrectHeaders() throws Exception {
        JavaMailSenderImpl dummySender = new JavaMailSenderImpl();
        MimeMessage mimeMessage = dummySender.createMimeMessage();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        String to = "recipient@example.com";
        String subject = "Booking Confirmation";
        String htmlBody = "<html><body><h1>Confirmed!</h1></body></html>";

        adapter.send(to, subject, htmlBody);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage sentMessage = captor.getValue();

        Address[] recipients = sentMessage.getRecipients(Message.RecipientType.TO);
        assertThat(recipients).hasSize(1);
        assertThat(recipients[0].toString()).isEqualTo("recipient@example.com");
        assertThat(sentMessage.getSubject()).isEqualTo("Booking Confirmation");

        assertThat(sentMessage.getContentType()).startsWith("multipart/");
        Object content = sentMessage.getContent();
        assertThat(content).isInstanceOf(Multipart.class);

        String htmlContent = findPartByMimeType((Multipart) content, "text/html");
        assertThat(htmlContent).isNotNull().contains("<h1>Confirmed!</h1>");
    }

    private String findPartByMimeType(Multipart multipart, String mimeType) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            jakarta.mail.BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType(mimeType)) {
                return part.getContent().toString();
            }
            if (part.getContent() instanceof Multipart nested) {
                String result = findPartByMimeType(nested, mimeType);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    @Test
    void send_whenMailSenderFails_rethrowsException() {
        JavaMailSenderImpl dummySender = new JavaMailSenderImpl();
        MimeMessage mimeMessage = dummySender.createMimeMessage();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("Mail server down")).when(mailSender).send(mimeMessage);

        assertThatThrownBy(() -> adapter.send("user@example.com", "Subject", "<p>Test</p>"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Mail server down");
    }
}
