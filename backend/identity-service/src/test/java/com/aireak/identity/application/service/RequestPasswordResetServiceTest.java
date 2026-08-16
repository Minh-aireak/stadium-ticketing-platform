package com.aireak.identity.application.service;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.config.PasswordResetProperties;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestPasswordResetServiceTest {

    private static final PasswordResetProperties TTL_30_MINUTES = new PasswordResetProperties(1800);

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PasswordResetTokenPort resetTokenPort;
    @Mock
    private DomainEventPublisher eventPublisher;

    private RequestPasswordResetService newService() {
        return new RequestPasswordResetService(accountRepository, resetTokenPort, eventPublisher, TTL_30_MINUTES);
    }

    @Test
    void storesAOneTimeTokenAndPublishesTheEventCarryingItsExpiryWindow() {
        Account account = activeAccount("forgetful@example.com");
        when(accountRepository.findByEmail(new Email("forgetful@example.com"))).thenReturn(Optional.of(account));
        when(resetTokenPort.generate()).thenReturn("generated-reset-token");

        newService().execute("forgetful@example.com");

        verify(resetTokenPort).store("generated-reset-token", account.getId());

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        PasswordResetRequestedEvent event = (PasswordResetRequestedEvent) published.getValue().get(0);
        assertThat(event.resetToken()).isEqualTo("generated-reset-token");
        assertThat(event.email().value()).isEqualTo("forgetful@example.com");
        assertThat(event.expiresInMinutes()).isEqualTo(30);
    }

    /**
     * The whole point of the endpoint's uniform response: an attacker must not be able to tell a
     * registered address from an unregistered one.
     */
    @Test
    void unknownEmailCompletesNormallyWithoutIssuingATokenOrPublishingAnything() {
        when(accountRepository.findByEmail(new Email("nobody@example.com"))).thenReturn(Optional.empty());

        assertThatCode(() -> newService().execute("nobody@example.com")).doesNotThrowAnyException();

        verify(resetTokenPort, never()).store(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void malformedEmailCompletesNormallyInsteadOfSurfacingAValidationError() {
        assertThatCode(() -> newService().execute("not-an-email")).doesNotThrowAnyException();

        verify(resetTokenPort, never()).store(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void nonActiveAccountCompletesNormallyAndLeavesNoUnusableTokenInRedis() {
        Account pending = Account.reconstitute(AccountId.generate(), new Email("pending@example.com"),
                new HashedPassword("$2a$12$hash"), AccountStatus.PENDING_VERIFICATION, Instant.now(),
                AccountRole.USER);
        when(accountRepository.findByEmail(new Email("pending@example.com"))).thenReturn(Optional.of(pending));
        when(resetTokenPort.generate()).thenReturn("generated-reset-token");

        assertThatCode(() -> newService().execute("pending@example.com")).doesNotThrowAnyException();

        verify(resetTokenPort, never()).store(any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    private Account activeAccount(String email) {
        return Account.reconstitute(AccountId.generate(), new Email(email),
                new HashedPassword("$2a$12$hash"), AccountStatus.ACTIVE, Instant.now(), AccountRole.USER);
    }
}
