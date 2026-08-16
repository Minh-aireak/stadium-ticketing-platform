package com.aireak.identity.domain.model;

import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Email EMAIL = new Email("user@example.com");
    private static final HashedPassword PASSWORD = new HashedPassword("$2a$12$hashedvalue");
    private static final String VERIFICATION_TOKEN = "test-verification-token";

    @Test
    void registerStartsInPendingVerificationAndRaisesAccountRegisteredEvent() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(account.getEmail()).isEqualTo(EMAIL);
        assertThat(account.getId()).isNotNull();
        List<Object> events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AccountRegisteredEvent.class);
        AccountRegisteredEvent registered = (AccountRegisteredEvent) events.get(0);
        assertThat(registered.accountId()).isEqualTo(account.getId());
        assertThat(registered.verificationToken()).isEqualTo(VERIFICATION_TOKEN);
    }

    @Test
    void activateFromPendingVerificationRaisesAccountActivatedEvent() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.pullDomainEvents();

        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        List<Object> events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AccountActivatedEvent.class);
    }

    @Test
    void activateRejectsWhenAlreadyActive() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.activate();

        assertThatThrownBy(account::activate).isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void suspendRequiresActiveStatus() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);

        assertThatThrownBy(account::suspend).isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void suspendFromActiveSucceeds() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.activate();

        account.suspend();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void reactivateRequiresSuspendedStatus() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.activate();

        assertThatThrownBy(account::reactivate).isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void reactivateFromSuspendedReturnsToActive() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.activate();
        account.suspend();

        account.reactivate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void changePasswordRequiresActiveStatus() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        HashedPassword newPassword = new HashedPassword("$2a$12$newhash");

        assertThatThrownBy(() -> account.changePassword(newPassword))
                .isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void changePasswordWhenActiveUpdatesThePassword() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.activate();
        HashedPassword newPassword = new HashedPassword("$2a$12$newhash");

        account.changePassword(newPassword);

        assertThat(account.getPassword()).isEqualTo(newPassword);
    }

    @Test
    void requestPasswordResetOnAnActiveAccountRaisesTheEventWithoutChangingState() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);
        account.activate();
        account.pullDomainEvents();

        account.requestPasswordReset("reset-token", 30);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getPassword()).isEqualTo(PASSWORD);
        List<Object> events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        PasswordResetRequestedEvent requested = (PasswordResetRequestedEvent) events.get(0);
        assertThat(requested.accountId()).isEqualTo(account.getId());
        assertThat(requested.email()).isEqualTo(EMAIL);
        assertThat(requested.resetToken()).isEqualTo("reset-token");
        assertThat(requested.expiresInMinutes()).isEqualTo(30);
    }

    @Test
    void requestPasswordResetRequiresActiveStatus() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);

        assertThatThrownBy(() -> account.requestPasswordReset("reset-token", 30))
                .isInstanceOf(InvalidAccountStatusException.class);
        assertThat(account.pullDomainEvents())
                .noneMatch(PasswordResetRequestedEvent.class::isInstance);
    }

    @Test
    void pullDomainEventsClearsTheList() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);

        List<Object> firstPull = account.pullDomainEvents();
        List<Object> secondPull = account.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }

    @Test
    void reconstituteRaisesNoEventsAndPreservesState() {
        Instant registeredAt = Instant.parse("2024-01-01T00:00:00Z");

        Account account = Account.reconstitute(AccountId.generate(), EMAIL, PASSWORD,
                AccountStatus.SUSPENDED, registeredAt, AccountRole.USER);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(account.getRegisteredAt()).isEqualTo(registeredAt);
        assertThat(account.pullDomainEvents()).isEmpty();
    }

    @Test
    void registerDefaultsToUserRole() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);

        assertThat(account.getRole()).isEqualTo(AccountRole.USER);
    }

    @Test
    void registerAdminIsActiveWithAdminRoleAndRaisesNoEvents() {
        Account account = Account.registerAdmin(EMAIL, PASSWORD);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(account.pullDomainEvents()).isEmpty();
    }

    @Test
    void promoteToAdminEscalatesRoleAndForcesActiveEvenFromPendingVerification() {
        Account account = Account.register(EMAIL, PASSWORD, VERIFICATION_TOKEN);

        account.promoteToAdmin();

        assertThat(account.getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }
}
