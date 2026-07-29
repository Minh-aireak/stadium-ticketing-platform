package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.application.port.out.dto.IssuedRefreshToken;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final Email EMAIL = new Email("user@example.com");
    private static final HashedPassword STORED_HASH = new HashedPassword("$2a$12$storedHash");
    private static final HashedPassword DUMMY_HASH = new HashedPassword("$2a$12$dummyHash");
    private static final String VERIFICATION_TOKEN = "test-verification-token";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PasswordHashPort passwordHashPort;
    @Mock
    private TokenGeneratorPort tokenGeneratorPort;
    @Mock
    private RefreshSessionStorePort refreshSessionStorePort;

    private final JwtProperties jwtProperties = new JwtProperties("secret", 900, "issuer", "audience");

    private LoginService service;

    @BeforeEach
    void setUp() {
        // @PostConstruct isn't invoked by plain `new` outside a Spring container.
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(DUMMY_HASH);
        service = new LoginService(accountRepository, passwordHashPort, tokenGeneratorPort,
                refreshSessionStorePort, jwtProperties);
        service.initDummyHash();
    }

    private Account activeAccount() {
        Account account = Account.register(EMAIL, STORED_HASH, VERIFICATION_TOKEN);
        account.activate();
        account.pullDomainEvents();
        return account;
    }

    @Test
    void successfulLoginReturnsAccessTokenAndRefreshSession() {
        Account account = activeAccount();
        when(accountRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(account));
        when(passwordHashPort.matches(any(RawPassword.class), eq(STORED_HASH))).thenReturn(true);
        when(tokenGeneratorPort.generateToken(account)).thenReturn("signed.jwt.token");
        IssuedRefreshToken issued = new IssuedRefreshToken("session-1", "session-1",
                account.getId().toString(), "raw-refresh-token", Instant.now().plusSeconds(2_592_000));
        when(refreshSessionStorePort.createSession(account.getId())).thenReturn(issued);

        AuthResult result = service.execute(new LoginCommand(EMAIL.value(), "Abcdefg1"));

        assertThat(result.accessToken()).isEqualTo("signed.jwt.token");
        assertThat(result.refreshToken()).isEqualTo(issued);
    }

    @Test
    void wrongPasswordIsRejectedWithAGenericMessage() {
        Account account = activeAccount();
        when(accountRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(account));
        when(passwordHashPort.matches(any(RawPassword.class), eq(STORED_HASH))).thenReturn(false);

        assertThatThrownBy(() -> service.execute(new LoginCommand(EMAIL.value(), "WrongPass1")))
                .isInstanceOf(InvalidAccountStatusException.class)
                .hasMessage("Invalid credentials");
        verify(tokenGeneratorPort, never()).generateToken(any());
        verify(refreshSessionStorePort, never()).createSession(any());
    }

    @Test
    void unknownEmailIsRejectedWithTheSameGenericMessageAsWrongPassword() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.execute(new LoginCommand(EMAIL.value(), "Abcdefg1")))
                .isInstanceOf(InvalidAccountStatusException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void unknownEmailStillPaysTheBcryptCostAgainstTheDummyHashToPreventTimingEnumeration() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.empty());

        try {
            service.execute(new LoginCommand(EMAIL.value(), "Abcdefg1"));
        } catch (InvalidAccountStatusException ignored) {
            // expected — asserting the timing-mitigation call happened, not the outcome
        }

        ArgumentCaptor<HashedPassword> comparedAgainst = ArgumentCaptor.forClass(HashedPassword.class);
        verify(passwordHashPort).matches(any(RawPassword.class), comparedAgainst.capture());
        assertThat(comparedAgainst.getValue()).isEqualTo(DUMMY_HASH);
    }

    @Test
    void inactiveAccountIsRejectedWithTheSameGenericMessage() {
        Account pending = Account.register(EMAIL, STORED_HASH, VERIFICATION_TOKEN); // still PENDING_VERIFICATION
        when(accountRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(pending));
        when(passwordHashPort.matches(any(RawPassword.class), eq(STORED_HASH))).thenReturn(true);

        assertThatThrownBy(() -> service.execute(new LoginCommand(EMAIL.value(), "Abcdefg1")))
                .isInstanceOf(InvalidAccountStatusException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void suspendedAccountIsRejectedEvenWithTheCorrectPassword() {
        Account account = Account.register(EMAIL, STORED_HASH, VERIFICATION_TOKEN);
        account.activate();
        account.suspend();
        when(accountRepository.findByEmail(EMAIL)).thenReturn(java.util.Optional.of(account));
        when(passwordHashPort.matches(any(RawPassword.class), eq(STORED_HASH))).thenReturn(true);

        assertThatThrownBy(() -> service.execute(new LoginCommand(EMAIL.value(), "Abcdefg1")))
                .isInstanceOf(InvalidAccountStatusException.class);
        verify(tokenGeneratorPort, never()).generateToken(any());
    }
}
