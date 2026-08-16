package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.command.ResetPasswordCommand;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.exception.InvalidPasswordException;
import com.aireak.identity.domain.exception.InvalidPasswordResetTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.AccountRole;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResetPasswordServiceTest {

    @Mock
    private PasswordResetTokenPort resetTokenPort;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PasswordHashPort passwordHashPort;
    @Mock
    private RefreshSessionStorePort refreshSessionStore;

    private ResetPasswordService newService() {
        return new ResetPasswordService(resetTokenPort, accountRepository, passwordHashPort,
                refreshSessionStore);
    }

    @Test
    void consumesTheTokenAndStoresTheNewlyHashedPassword() {
        Account account = activeAccount();
        HashedPassword newHash = new HashedPassword("$2a$12$newHash");
        when(resetTokenPort.consume("reset-tok")).thenReturn(account.getId());
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(newHash);

        newService().execute(new ResetPasswordCommand("reset-tok", "NewPassw0rd"));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo(newHash);
    }

    /**
     * The point of resetting a password on a compromised account: the intruder's refresh session
     * must not outlive it.
     */
    @Test
    void revokesEveryRefreshSessionOnceTheNewPasswordIsStored() {
        Account account = activeAccount();
        when(resetTokenPort.consume("reset-tok")).thenReturn(account.getId());
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(new HashedPassword("$2a$12$newHash"));

        newService().execute(new ResetPasswordCommand("reset-tok", "NewPassw0rd"));

        InOrder inOrder = inOrder(accountRepository, refreshSessionStore);
        inOrder.verify(accountRepository).save(any(Account.class));
        inOrder.verify(refreshSessionStore).revokeAllForAccount(account.getId());
    }

    @Test
    void aRejectedResetLeavesExistingSessionsAlone() {
        when(resetTokenPort.consume("stale-tok"))
                .thenThrow(new InvalidPasswordResetTokenException("Password reset token is invalid or has expired"));

        assertThatThrownBy(() -> newService().execute(new ResetPasswordCommand("stale-tok", "NewPassw0rd")))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        verify(refreshSessionStore, never()).revokeAllForAccount(any());
    }

    /** A rejected password must not burn the customer's only reset link. */
    @Test
    void weakPasswordIsRejectedBeforeTheTokenIsConsumed() {
        assertThatThrownBy(() -> newService().execute(new ResetPasswordCommand("reset-tok", "weak")))
                .isInstanceOf(InvalidPasswordException.class);

        verify(resetTokenPort, never()).consume(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void expiredOrUnknownTokenIsRejectedWithoutTouchingAnyAccount() {
        when(resetTokenPort.consume("stale-tok"))
                .thenThrow(new InvalidPasswordResetTokenException("Password reset token is invalid or has expired"));

        assertThatThrownBy(() -> newService().execute(new ResetPasswordCommand("stale-tok", "NewPassw0rd")))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void tokenPointingAtAMissingAccountIsReportedAsAnInvalidToken() {
        AccountId accountId = AccountId.generate();
        when(resetTokenPort.consume("orphan-tok")).thenReturn(accountId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().execute(new ResetPasswordCommand("orphan-tok", "NewPassw0rd")))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void suspendedAccountCannotHaveItsPasswordReset() {
        Account suspended = Account.reconstitute(AccountId.generate(), new Email("banned@example.com"),
                new HashedPassword("$2a$12$hash"), AccountStatus.SUSPENDED, Instant.now(), AccountRole.USER);
        when(resetTokenPort.consume("reset-tok")).thenReturn(suspended.getId());
        when(accountRepository.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(new HashedPassword("$2a$12$newHash"));

        assertThatThrownBy(() -> newService().execute(new ResetPasswordCommand("reset-tok", "NewPassw0rd")))
                .isInstanceOf(InvalidAccountStatusException.class);

        verify(accountRepository, never()).save(any());
    }

    private Account activeAccount() {
        return Account.reconstitute(AccountId.generate(), new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), AccountStatus.ACTIVE, Instant.now(), AccountRole.USER);
    }
}
