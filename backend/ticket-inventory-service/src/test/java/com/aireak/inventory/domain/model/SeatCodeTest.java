package com.aireak.inventory.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"A1", "B12", "Z999"})
    void acceptsValidFormats(String value) {
        assertThat(new SeatCode(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1A", "a1", "A", "A1234", "A-1", ""})
    void rejectsInvalidFormats(String value) {
        assertThatThrownBy(() -> new SeatCode(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new SeatCode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringReturnsRawValue() {
        assertThat(new SeatCode("A1")).hasToString("A1");
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        assertThat(new SeatCode("A1")).isEqualTo(new SeatCode("A1"));
        assertThat(new SeatCode("A1")).hasSameHashCodeAs(new SeatCode("A1"));
        assertThat(new SeatCode("A1")).isNotEqualTo(new SeatCode("A2"));
    }
}
