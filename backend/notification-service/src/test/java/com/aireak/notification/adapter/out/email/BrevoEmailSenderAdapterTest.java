package com.aireak.notification.adapter.out.email;

import com.aireak.notification.application.port.out.dto.EmailMessage;
import com.aireak.notification.config.BrevoProperties;
import com.aireak.notification.domain.exception.EmailDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Asserts the exact wire contract with Brevo's Transactional Email API — the endpoint, the
 * {@code api-key} header, and the {@code sender}/{@code to}/{@code htmlContent}/{@code textContent}
 * body shape — plus the two failure modes that matter operationally: an IP that isn't on the
 * account's allow-list, and everything else.
 */
class BrevoEmailSenderAdapterTest {

    private static final BrevoProperties PROPERTIES = new BrevoProperties(
            "xkeysib-test-key", "no-reply@stadium.test", "Stadium Ticketing",
            "https://api.brevo.com", 5000, 10000);

    private MockRestServiceServer server;
    private BrevoEmailSenderAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .defaultHeader("api-key", PROPERTIES.apiKey());
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new BrevoEmailSenderAdapter(PROPERTIES, builder.build());
    }

    @Test
    void send_postsTransactionalEmailEndpointWithApiKeyAndBothBodyParts() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("no-reply@stadium.test"))
                .andExpect(jsonPath("$.sender.name").value("Stadium Ticketing"))
                .andExpect(jsonPath("$.to[0].email").value("customer@example.com"))
                .andExpect(jsonPath("$.to[0].name").value("customer"))
                .andExpect(jsonPath("$.subject").value("Payment received"))
                .andExpect(jsonPath("$.htmlContent").value("<p>Thanks!</p>"))
                .andExpect(jsonPath("$.textContent").value("Thanks!"))
                .andRespond(withSuccess("{\"messageId\":\"<202608161200.1@smtp-relay.brevo.com>\"}",
                        MediaType.APPLICATION_JSON));

        adapter.send(new EmailMessage("customer@example.com", "customer", "Payment received",
                "<p>Thanks!</p>", "Thanks!"));

        server.verify();
    }

    @Test
    void send_omitsRecipientNameEntirelyWhenUnknownRatherThanSendingNull() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(jsonPath("$.to[0].name").doesNotExist())
                .andRespond(withSuccess("{\"messageId\":\"<id@brevo>\"}", MediaType.APPLICATION_JSON));

        adapter.send(new EmailMessage("customer@example.com", null, "Subject", "<p>x</p>", "x"));

        server.verify();
    }

    @Test
    void send_whenIpIsNotAuthorized_failsWithTheDashboardRemediationStepInTheMessage() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\","
                                + "\"message\":\"Unrecognised IP address 203.0.113.7. "
                                + "To use the API from this IP address, please authorise it.\"}"));

        assertThatThrownBy(() -> adapter.send(
                new EmailMessage("customer@example.com", "customer", "Subject", "<p>x</p>", "x")))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("203.0.113.7")
                .hasMessageContaining("Settings > Security > Authorized IPs");
    }

    @Test
    void send_whenBrevoRejectsForAnyOtherReason_failsWithTheProvidersOwnResponseBody() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"invalid_parameter\",\"message\":\"sender is not valid\"}"));

        assertThatThrownBy(() -> adapter.send(
                new EmailMessage("customer@example.com", "customer", "Subject", "<p>x</p>", "x")))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("sender is not valid")
                // Only a genuine IP rejection should carry the allow-list hint.
                .hasMessageNotContaining("Authorized IPs");
    }

    @Test
    void send_neverLeaksTheApiKeyIntoTheFailureMessage() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        assertThatThrownBy(() -> adapter.send(
                new EmailMessage("customer@example.com", "customer", "Subject", "<p>x</p>", "x")))
                .isInstanceOf(EmailDeliveryException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("xkeysib-test-key"));
    }
}
