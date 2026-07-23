package com.aireak.booking.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingAmountTest {

    @Test
    void acceptsAPositiveAmountWithAThreeLetterCurrency() {
        BookingAmount amount = BookingAmount.of(new BigDecimal("99.99"), "USD");

        assertThat(amount.amount()).isEqualByComparingTo("99.99");
        assertThat(amount.currency()).isEqualTo("USD");
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> BookingAmount.of(BigDecimal.ZERO, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> BookingAmount.of(new BigDecimal("-10.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> BookingAmount.of(null, "USD"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> BookingAmount.of(new BigDecimal("10.00"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsCurrencyCodeShorterThanThreeLetters() {
        assertThatThrownBy(() -> BookingAmount.of(new BigDecimal("10.00"), "US"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCurrencyCodeLongerThanThreeLetters() {
        assertThatThrownBy(() -> BookingAmount.of(new BigDecimal("10.00"), "USDT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Documents a real gap: BookingAmount only checks currency.length() == 3, not that it's
    // actually a valid ISO 4217 code — any 3 characters pass, including non-letters.
    @Test
    void currentlyAcceptsAnyThreeCharacterStringAsCurrencyNotJustValidIso4217Codes() {
        BookingAmount amount = BookingAmount.of(new BigDecimal("10.00"), "XYZ");

        assertThat(amount.currency()).isEqualTo("XYZ");
    }
}
