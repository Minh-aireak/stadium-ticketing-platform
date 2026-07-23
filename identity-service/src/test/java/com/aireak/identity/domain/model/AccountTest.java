package com.aireak.identity.domain.model;

import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Email EMAIL = new Email("user@example.com");
    private static final HashedPassword PASSWORD = new HashedPassword("$2a$12$hashedvalue");

    @Test
    void registerStartsInPendingVerificationAndRaisesAccountRegisteredEvent() {
        Account account = Account.register(EMAIL, PASSWORD);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(account.getEmail()).isEqualTo(EMAIL);
        assertThat(account.getId()).isNotNull();
        List<Object> events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AccountRegisteredEvent.class);
        assertThat(((AccountRegisteredEvent) events.get(0)).accountId()).isEqualTo(account.getId());
    }

    @Test
    void activateFromPendingVerificationRaisesAccountActivatedEvent() {
        Account account = Account.register(EMAIL, PASSWORD);
        account.pullDomainEvents();

        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        List<Object> events = account.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AccountActivatedEvent.class);
    }

    @Test
    void activateRejectsWhenAlreadyActive() {
        Account account = Account.register(EMAIL, PASSWORD);
        account.activate();

        assertThatThrownBy(account::activate).isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void suspendRequiresActiveStatus() {
        Account account = Account.register(EMAIL, PASSWORD);

        assertThatThrownBy(account::suspend).isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void suspendFromActiveSucceeds() {
        Account account = Account.register(EMAIL, PASSWORD);
        account.activate();

        account.suspend();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void reactivateRequiresSuspendedStatus() {
        Account account = Account.register(EMAIL, PASSWORD);
        account.activate();

        assertThatThrownBy(account::reactivate).isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void reactivateFromSuspendedReturnsToActive() {
        Account account = Account.register(EMAIL, PASSWORD);
        account.activate();
        account.suspend();

        account.reactivate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void changePasswordRequiresActiveStatus() {
        Account account = Account.register(EMAIL, PASSWORD);
        HashedPassword newPassword = new HashedPassword("$2a$12$newhash");

        assertThatThrownBy(() -> account.changePassword(newPassword))
                .isInstanceOf(InvalidAccountStatusException.class);
    }

    @Test
    void changePasswordWhenActiveUpdatesThePassword() {
        Account account = Account.register(EMAIL, PASSWORD);
        account.activate();
        HashedPassword newPassword = new HashedPassword("$2a$12$newhash");

        account.changePassword(newPassword);

        assertThat(account.getPassword()).isEqualTo(newPassword);
    }

    @Test
    void pullDomainEventsClearsTheList() {
        Account account = Account.register(EMAIL, PASSWORD);

        List<Object> firstPull = account.pullDomainEvents();
        List<Object> secondPull = account.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }

    @Test
    void reconstituteRaisesNoEventsAndPreservesState() {
        Instant registeredAt = Instant.parse("2024-01-01T00:00:00Z");

        Account account = Account.reconstitute(AccountId.generate(), EMAIL, PASSWORD,
                AccountStatus.SUSPENDED, registeredAt);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(account.getRegisteredAt()).isEqualTo(registeredAt);
        assertThat(account.pullDomainEvents()).isEmpty();
    }
}
