package com.aireak.identity.application.service;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.AccountRole;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code RequestPasswordResetServiceTest}: the interesting property is that every
 * rejection path is indistinguishable from success to the caller, so this endpoint cannot be used
 * to sort addresses into registered, unregistered, and registered-but-unverified.
 */
@ExtendWith(MockitoExtension.class)
class ResendVerificationServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private EmailVerificationTokenPort verificationTokenPort;
    @Mock
    private DomainEventPublisher eventPublisher;

    private ResendVerificationService newService() {
        return new ResendVerificationService(accountRepository, verificationTokenPort, eventPublisher);
    }

    @Test
    void mintsAFreshTokenAndRepublishesTheWelcomeEventForAPendingAccount() {
        Account pending = Account.register(new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), "original-token");
        pending.pullDomainEvents();
        when(accountRepository.findByEmail(new Email("user@example.com"))).thenReturn(Optional.of(pending));
        when(verificationTokenPort.generate()).thenReturn("fresh-token");

        newService().execute("user@example.com");

        verify(verificationTokenPort).store("fresh-token", pending.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).singleElement()
                .isInstanceOfSatisfying(AccountRegisteredEvent.class, event -> {
                    assertThat(event.accountId()).isEqualTo(pending.getId());
                    // The fresh token, not the one from the mail that never arrived.
                    assertThat(event.verificationToken()).isEqualTo("fresh-token");
                });
    }

    @Test
    void anUnknownAddressIsSilentlyIgnored() {
        when(accountRepository.findByEmail(new Email("nobody@example.com"))).thenReturn(Optional.empty());

        newService().execute("nobody@example.com");

        verify(verificationTokenPort, never()).store(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void aMalformedAddressIsSilentlyIgnored() {
        newService().execute("not-an-email");

        verify(verificationTokenPort, never()).store(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    /**
     * The distinction this endpoint must not leak: an address that is registered AND already
     * verified has to look exactly like one that was never registered at all.
     */
    @Test
    void anAlreadyVerifiedAccountIsSilentlyIgnored() {
        Account active = Account.reconstitute(AccountId.generate(), new Email("user@example.com"),
                new HashedPassword("$2a$12$hash"), AccountStatus.ACTIVE, Instant.now(), AccountRole.USER);
        when(accountRepository.findByEmail(new Email("user@example.com"))).thenReturn(Optional.of(active));
        when(verificationTokenPort.generate()).thenReturn("fresh-token");

        newService().execute("user@example.com");

        // Generated before the status is known, but never bound to the account: an unusable token
        // must not be left sitting in Redis.
        verify(verificationTokenPort, never()).store(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }
}
