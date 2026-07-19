package com.aireak.identity.domain.model;

import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregate Root: Account — Identity & Access bounded context.
 *
 * <p>Invariants protected:
 * <ul>
 *   <li>Status transitions are strictly controlled (no arbitrary state assignment)</li>
 *   <li>Only PENDING_VERIFICATION accounts can be activated</li>
 *   <li>Only ACTIVE accounts can be suspended</li>
 *   <li>Password can only be changed when ACTIVE</li>
 * </ul>
 *
 * <p>Domain events are collected and published by the application layer
 * after successful persistence — NOT inside this class.
 *
 * <p>No JPA annotations here — this is a pure domain object.
 */
public class Account {

    private final AccountId id;
    private Email email;
    private HashedPassword password;
    private AccountStatus status;
    private final Instant registeredAt;
    private final List<Object> domainEvents = new ArrayList<>();

    /**
     * Private constructor — use factory method {@link #register}.
     */
    private Account(AccountId id, Email email, HashedPassword password,
                    AccountStatus status, Instant registeredAt) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.status = status;
        this.registeredAt = registeredAt;
    }

    // ----------------------------------------------------------------
    // Factory method
    // ----------------------------------------------------------------

    /**
     * Creates a new Account in PENDING_VERIFICATION state.
     * Records {@link AccountRegisteredEvent} for async notification.
     */
    public static Account register(Email email, HashedPassword password) {
        AccountId id = AccountId.generate();
        Account account = new Account(id, email, password,
                AccountStatus.PENDING_VERIFICATION, Instant.now());
        account.domainEvents.add(new AccountRegisteredEvent(id, email));
        return account;
    }

    /**
     * Reconstitutes an Account from persistence (no events raised).
     * Used by the persistence adapter to rebuild state from DB.
     */
    public static Account reconstitute(AccountId id, Email email, HashedPassword password,
                                       AccountStatus status, Instant registeredAt) {
        return new Account(id, email, password, status, registeredAt);
    }

    // ----------------------------------------------------------------
    // Domain behavior
    // ----------------------------------------------------------------

    /**
     * Activates account after email verification.
     * Invariant: must be in PENDING_VERIFICATION state.
     */
    public void activate() {
        if (status != AccountStatus.PENDING_VERIFICATION) {
            throw new InvalidAccountStatusException(
                    "Account can only be activated from PENDING_VERIFICATION, current: " + status);
        }
        this.status = AccountStatus.ACTIVE;
        domainEvents.add(new AccountActivatedEvent(id, email));
    }

    /**
     * Suspends an active account.
     * Invariant: must be ACTIVE.
     */
    public void suspend() {
        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStatusException(
                    "Only ACTIVE accounts can be suspended, current: " + status);
        }
        this.status = AccountStatus.SUSPENDED;
    }

    /**
     * Re-activates a suspended account.
     * Invariant: must be SUSPENDED.
     */
    public void reactivate() {
        if (status != AccountStatus.SUSPENDED) {
            throw new InvalidAccountStatusException(
                    "Only SUSPENDED accounts can be reactivated, current: " + status);
        }
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Changes password. Invariant: account must be ACTIVE.
     */
    public void changePassword(HashedPassword newPassword) {
        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStatusException(
                    "Password can only be changed when ACTIVE, current: " + status);
        }
        this.password = newPassword;
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public AccountId getId()          { return id; }
    public Email getEmail()           { return email; }
    public HashedPassword getPassword() { return password; }
    public AccountStatus getStatus()  { return status; }
    public Instant getRegisteredAt()  { return registeredAt; }

    /**
     * Returns domain events accumulated during this session.
     * Application layer calls this after persist, then clears.
     */
    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }
}
