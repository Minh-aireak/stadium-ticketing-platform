package com.aireak.identity.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void acceptsAWellFormedAddress() {
        Email email = new Email("user@example.com");

        assertThat(email.value()).isEqualTo("user@example.com");
    }

    @Test
    void normalizesToLowercase() {
        Email email = new Email("User@Example.COM");

        assertThat(email.value()).isEqualTo("user@example.com");
    }

    @Test
    void trimsLeadingAndTrailingWhitespaceBeforeValidating() {
        assertThat(new Email("  user@example.com").value()).isEqualTo("user@example.com");
        assertThat(new Email("user@example.com  ").value()).isEqualTo("user@example.com");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new Email(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsMissingAtSign() {
        assertThatThrownBy(() -> new Email("not-an-email")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingDomainDot() {
        assertThatThrownBy(() -> new Email("user@localhost")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhitespaceInsideTheAddress() {
        assertThatThrownBy(() -> new Email("us er@example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringReturnsTheValueNotAJavaRecordRepresentation() {
        Email email = new Email("user@example.com");

        assertThat(email.toString()).isEqualTo("user@example.com");
    }
}
