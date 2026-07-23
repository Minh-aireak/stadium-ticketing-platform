package com.aireak.identity.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawPasswordTest {

    @Test
    void acceptsAPasswordMeetingThePolicy() {
        RawPassword password = new RawPassword("Abcdefg1");

        assertThat(password.exposeForHashing()).isEqualTo("Abcdefg1");
    }

    @Test
    void rejectsShorterThanEightCharacters() {
        assertThatThrownBy(() -> new RawPassword("Ab1defg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 characters");
    }

    @Test
    void rejectsMissingUppercase() {
        assertThatThrownBy(() -> new RawPassword("abcdefg1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase");
    }

    @Test
    void rejectsMissingLowercase() {
        assertThatThrownBy(() -> new RawPassword("ABCDEFG1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    void rejectsMissingDigit() {
        assertThatThrownBy(() -> new RawPassword("Abcdefgh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digit");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new RawPassword(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void forAuthenticationSkipsThePolicyCheckSoAnOlderValidPasswordCanStillBeVerified() {
        // A password valid under a since-tightened policy (e.g. no digit required, back when
        // the account registered) must still be checkable at login — LoginService must reject
        // on a wrong password, never on a policy mismatch against a password that was legitimate
        // when it was set.
        RawPassword password = RawPassword.forAuthentication("nopolicycompliance");

        assertThat(password.exposeForHashing()).isEqualTo("nopolicycompliance");
    }

    @Test
    void forAuthenticationStillRejectsNull() {
        assertThatThrownBy(() -> RawPassword.forAuthentication(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringNeverExposesTheRawValue() {
        RawPassword password = new RawPassword("Abcdefg1");

        assertThat(password.toString()).isEqualTo("***");
    }
}
