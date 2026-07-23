package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.application.port.out.dto.IssuedRefreshToken;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.exception.InvalidRefreshTokenException;
import com.aireak.identity.domain.exception.RefreshTokenReuseException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshSessionStorePort refreshSessionStorePort;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TokenGeneratorPort tokenGeneratorPort;

    private final JwtProperties jwtProperties = new JwtProperties("secret", 900, "issuer", "audience");

    private RefreshTokenService service() {
        return new RefreshTokenService(refreshSessionStorePort, accountRepository, tokenGeneratorPort, jwtProperties);
    }

    private Account activeAccount(AccountId id) {
        Account account = Account.reconstitute(id, new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), com.aireak.identity.domain.model.AccountStatus.ACTIVE,
                Instant.now());
        return account;
    }

    @Test
    void rejectsAMissingTokenWithoutTouchingTheSessionStore() {
        assertThatThrownBy(() -> service().execute(null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service().execute("  "))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshSessionStorePort, never()).rotate(any());
    }

    @Test
    void successfulRefreshReturnsANewAccessTokenAndTheRotatedSession() {
        AccountId accountId = AccountId.generate();
        IssuedRefreshToken rotated = new IssuedRefreshToken("session-1", "session-1",
                accountId.toString(), "new-raw-token", Instant.now().plusSeconds(1000));
        when(refreshSessionStorePort.rotate("old-raw-token")).thenReturn(rotated);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(accountId)));
        when(tokenGeneratorPort.generateToken(any(Account.class))).thenReturn("new.access.token");

        AuthResult result = service().execute("old-raw-token");

        assertThat(result.accessToken()).isEqualTo("new.access.token");
        assertThat(result.refreshToken()).isEqualTo(rotated);
    }

    @Test
    void tokenReuseExceptionFromRotationPropagatesWithoutLookingUpTheAccount() {
        when(refreshSessionStorePort.rotate("stolen-token"))
                .thenThrow(new RefreshTokenReuseException("reuse detected"));

        assertThatThrownBy(() -> service().execute("stolen-token"))
                .isInstanceOf(RefreshTokenReuseException.class);

        verify(accountRepository, never()).findById(any());
    }

    @Test
    void rejectsWhenTheRotatedSessionsAccountNoLongerExists() {
        AccountId accountId = AccountId.generate();
        IssuedRefreshToken rotated = new IssuedRefreshToken("session-1", "session-1",
                accountId.toString(), "new-raw-token", Instant.now().plusSeconds(1000));
        when(refreshSessionStorePort.rotate("raw-token")).thenReturn(rotated);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().execute("raw-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // Documents a real (minor) quirk: rotation in Redis has already succeeded and issued a new
    // token by the time this status check runs — the client gets rejected here and their cookie
    // gets cleared (see AuthController's exception handlers), so the newly rotated token is
    // never actually used by anyone. Not a security issue (a suspended account SHOULD be denied),
    // just an orphaned Redis session sitting until its absolute TTL expires.
    @Test
    void rejectsRefreshForANonActiveAccountEvenThoughRotationAlreadySucceeded() {
        AccountId accountId = AccountId.generate();
        IssuedRefreshToken rotated = new IssuedRefreshToken("session-1", "session-1",
                accountId.toString(), "new-raw-token", Instant.now().plusSeconds(1000));
        when(refreshSessionStorePort.rotate("raw-token")).thenReturn(rotated);
        Account suspended = Account.reconstitute(accountId, new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), com.aireak.identity.domain.model.AccountStatus.SUSPENDED,
                Instant.now());
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service().execute("raw-token"))
                .isInstanceOf(InvalidAccountStatusException.class);

        verify(tokenGeneratorPort, never()).generateToken(any());
    }
}
