package com.aireak.identity.domain.model;

import com.aireak.identity.domain.exception.InvalidPasswordException;
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
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessageContaining("8 characters");
    }

    @Test
    void rejectsMissingUppercase() {
        assertThatThrownBy(() -> new RawPassword("abcdefg1"))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessageContaining("uppercase");
    }

    @Test
    void rejectsMissingLowercase() {
        assertThatThrownBy(() -> new RawPassword("ABCDEFG1"))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    void rejectsMissingDigit() {
        assertThatThrownBy(() -> new RawPassword("Abcdefgh"))
                .isInstanceOf(InvalidPasswordException.class)
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

    /**
     * The policy predicates are Unicode-aware by design: {@code Character.isUpperCase} is
     * "general category UPPERCASE_LETTER, or the contributory property Other_Uppercase", and
     * {@code isDigit} is DECIMAL_DIGIT_NUMBER. Nothing here asserted that, so the entire
     * non-ASCII half of the policy was unpinned — on a Vietnamese-language product, where a
     * password built around a name like "Ánh" is entirely ordinary.
     *
     * <p>The frontend mirrors these three predicates as /\p{Uppercase}/u, /\p{Lowercase}/u and
     * /\p{Nd}/u (see passwordPolicy.ts), so any divergence here is a client waving through a
     * password this constructor rejects.
     */
    @Test
    void acceptsAnAccentedLetterAsItsOnlyUppercase() {
        RawPassword password = new RawPassword("Ánhxinh1");

        assertThat(password.exposeForHashing()).isEqualTo("Ánhxinh1");
    }

    /**
     * The regression. validate walked the password with {@code String.chars()}, which yields
     * UTF-16 code units rather than code points, so every supplementary-plane character reached
     * {@code Character.isUpperCase(int)} as two lone surrogates — and a lone surrogate is neither
     * a letter nor a digit. A password whose only uppercase letter lives above U+FFFF was
     * rejected for having "no uppercase letter", while the frontend schema and
     * {@code Character.isUpperCase} applied to the real code point both accept it.
     *
     * <p>U+1E900 is ADLAM CAPITAL ALIF — a living script's capital letter, not a curiosity.
     */
    @Test
    void acceptsASupplementaryPlaneLetterAsItsOnlyUppercase() {
        String password = new String(Character.toChars(0x1E900)) + "bcdefg1";

        assertThat(new RawPassword(password).exposeForHashing()).isEqualTo(password);
    }

    /** The same defect on the digit rule: U+1D7CF is MATHEMATICAL BOLD DIGIT ONE, category Nd. */
    @Test
    void acceptsASupplementaryPlaneDigitAsItsOnlyDigit() {
        String password = "Abcdefgh" + new String(Character.toChars(0x1D7CF));

        assertThat(new RawPassword(password).exposeForHashing()).isEqualTo(password);
    }

    @Test
    void toStringNeverExposesTheRawValue() {
        RawPassword password = new RawPassword("Abcdefg1");

        assertThat(password.toString()).isEqualTo("***");
    }
}
