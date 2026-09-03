package com.aireak.identity.domain.model;

import com.aireak.identity.domain.exception.InvalidEmailException;
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
        assertThatThrownBy(() -> new Email("not-an-email")).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void rejectsMissingDomainDot() {
        assertThatThrownBy(() -> new Email("user@localhost")).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void rejectsWhitespaceInsideTheAddress() {
        assertThatThrownBy(() -> new Email("us er@example.com")).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void toStringReturnsTheValueNotAJavaRecordRepresentation() {
        Email email = new Email("user@example.com");

        assertThat(email.toString()).isEqualTo("user@example.com");
    }

    /**
     * accounts.email is VARCHAR(255). Nothing bounded the address before: @Email on
     * RegisterRequest checks shape and not length, and the pattern here accepts a local part of
     * any size -- so an over-long address was accepted all the way to the INSERT, where Postgres
     * raised "value too long for type character varying(255)". That arrives as a
     * DataIntegrityViolationException at commit, which GlobalExceptionHandler can only answer as
     * 409 "The request conflicts with existing data".
     */
    @Test
    void rejectsAnAddressLongerThanTheAccountsEmailColumnCanHold() {
        String tooLong = "a".repeat(243) + "@example.com"; // 255 characters

        assertThatThrownBy(() -> new Email(tooLong))
                .isInstanceOf(InvalidEmailException.class)
                .hasMessageContaining("254");
    }

    @Test
    void acceptsAnAddressOfExactlyTheMaximumLength() {
        String atLimit = "a".repeat(242) + "@example.com"; // 254 characters

        assertThat(new Email(atLimit).value()).hasSize(254);
    }

    /** The bound applies to the trimmed value, not the raw one -- padding must not cost length. */
    @Test
    void measuresLengthAfterTrimming() {
        String atLimit = "a".repeat(242) + "@example.com";

        assertThat(new Email("  " + atLimit + "  ").value()).hasSize(254);
    }
}
