package com.aireak.identity.application.service;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.exception.InvalidVerificationTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.AccountRole;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The verification token lives in Redis and the account lives in Postgres, and only one of those
 * rolls back. These tests pin the order that follows from that: resolve, commit, then spend.
 */
@ExtendWith(MockitoExtension.class)
class VerifyEmailServiceTest {

    @Mock
    private EmailVerificationTokenPort verificationTokenPort;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private DomainEventPublisher eventPublisher;

    // The steps bean is built for real: the activation it performs is what every assertion is
    // about, and @Transactional is inert without a proxy.
    private VerifyEmailService newService() {
        return new VerifyEmailService(verificationTokenPort,
                new AccountActivationSteps(accountRepository, eventPublisher));
    }

    @Test
    void activatesTheAccountAndThenSpendsTheToken() {
        Account pending = pendingAccount();
        when(verificationTokenPort.peek("verify-tok")).thenReturn(pending.getId());
        when(accountRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        newService().execute("verify-tok");

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);

        InOrder inOrder = inOrder(accountRepository, eventPublisher, verificationTokenPort);
        inOrder.verify(accountRepository).save(any(Account.class));
        inOrder.verify(eventPublisher).publishAll(any());
        inOrder.verify(verificationTokenPort).invalidate("verify-tok");
    }

    /**
     * The regression this whole ordering exists for. The token used to be deleted in the same
     * atomic get-and-delete that read it, before any of this ran — so a failure here rolled the
     * account back to PENDING_VERIFICATION and left the customer's link dead, with no way to log
     * in, re-register, or ask for another one.
     */
    @Test
    void aFailedActivationLeavesTheTokenUsable() {
        Account pending = pendingAccount();
        when(verificationTokenPort.peek("verify-tok")).thenReturn(pending.getId());
        when(accountRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        doThrow(new IllegalStateException("connection reset on commit"))
                .when(accountRepository).save(any(Account.class));

        assertThatThrownBy(() -> newService().execute("verify-tok"))
                .isInstanceOf(IllegalStateException.class);

        verify(verificationTokenPort, never()).invalidate(any());
    }

    /**
     * Two clicks arriving together can now both resolve the same token, because reading it no
     * longer deletes it. The loser must read as success, not as an error page about an account
     * that is in fact verified.
     */
    @Test
    void anAlreadyActiveAccountIsANoOpAndStillSpendsTheToken() {
        Account active = Account.reconstitute(AccountId.generate(), new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), AccountStatus.ACTIVE, Instant.now(), AccountRole.USER);
        when(verificationTokenPort.peek("verify-tok")).thenReturn(active.getId());
        when(accountRepository.findById(active.getId())).thenReturn(Optional.of(active));

        newService().execute("verify-tok");

        verify(accountRepository, never()).save(any());
        // No second AccountActivatedEvent, so no duplicate "your account is active" email.
        verify(eventPublisher, never()).publishAll(any());
        verify(verificationTokenPort).invalidate("verify-tok");
    }

    /** Only ACTIVE is forgiven — activating a SUSPENDED account is a transition, not a replay. */
    @Test
    void aSuspendedAccountIsNotQuietlyActivated() {
        Account suspended = Account.reconstitute(AccountId.generate(), new Email("banned@example.com"),
                new HashedPassword("$2a$12$hash"), AccountStatus.SUSPENDED, Instant.now(), AccountRole.USER);
        when(verificationTokenPort.peek("verify-tok")).thenReturn(suspended.getId());
        when(accountRepository.findById(suspended.getId())).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> newService().execute("verify-tok"))
                .isInstanceOf(InvalidAccountStatusException.class);

        verify(accountRepository, never()).save(any());
        verify(verificationTokenPort, never()).invalidate(any());
    }

    @Test
    void anExpiredOrUnknownTokenTouchesNoAccount() {
        when(verificationTokenPort.peek("stale-tok"))
                .thenThrow(new InvalidVerificationTokenException("Verification token is invalid or has expired"));

        assertThatThrownBy(() -> newService().execute("stale-tok"))
                .isInstanceOf(InvalidVerificationTokenException.class);

        verify(accountRepository, never()).save(any());
        verify(verificationTokenPort, never()).invalidate(any());
    }

    @Test
    void aTokenPointingAtAMissingAccountIsReportedAsAnInvalidToken() {
        AccountId accountId = AccountId.generate();
        when(verificationTokenPort.peek("orphan-tok")).thenReturn(accountId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().execute("orphan-tok"))
                .isInstanceOf(InvalidVerificationTokenException.class);

        verify(verificationTokenPort, never()).invalidate(any());
    }

    private static Account pendingAccount() {
        Account account = Account.register(new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), "original-token");
        List<Object> ignored = account.pullDomainEvents();
        assertThat(ignored).hasSize(1);
        return account;
    }
}
