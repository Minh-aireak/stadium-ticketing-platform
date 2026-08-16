package com.aireak.identity.domain.model;

import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Account {

    private final AccountId id;
    private Email email;
    private HashedPassword password;
    private AccountStatus status;
    private AccountRole role;
    private final Instant registeredAt;
    private final List<Object> domainEvents = new ArrayList<>();

    private Account(AccountId id, Email email, HashedPassword password,
                    AccountStatus status, Instant registeredAt, AccountRole role) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.status = status;
        this.registeredAt = registeredAt;
        this.role = role;
    }

    // ----------------------------------------------------------------
    // Factory method
    // ----------------------------------------------------------------

    /**
     * Creates a new Account in PENDING_VERIFICATION state.
     * Records {@link AccountRegisteredEvent} for async notification.
     */
    public static Account register(Email email, HashedPassword password, String verificationToken) {
        AccountId id = AccountId.generate();
        Account account = new Account(id, email, password,
                AccountStatus.PENDING_VERIFICATION, Instant.now(), AccountRole.USER);
        account.domainEvents.add(new AccountRegisteredEvent(id, email, verificationToken));
        return account;
    }

    /**
     * Creates a new Account already ACTIVE with the ADMIN role, bypassing email verification.
     * Used only by the startup admin-bootstrap process (see {@code AdminBootstrapRunner}) — never
     * exposed via a public registration endpoint. No domain event is raised: there is no welcome/
     * verification email to send for an operator-provisioned admin.
     */
    public static Account registerAdmin(Email email, HashedPassword password) {
        AccountId id = AccountId.generate();
        return new Account(id, email, password, AccountStatus.ACTIVE, Instant.now(), AccountRole.ADMIN);
    }

    /**
     * Reconstitutes an Account from persistence (no events raised).
     * Used by the persistence adapter to rebuild state from DB.
     */
    public static Account reconstitute(AccountId id, Email email, HashedPassword password,
                                       AccountStatus status, Instant registeredAt, AccountRole role) {
        return new Account(id, email, password, status, registeredAt, role);
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
     * Records a password-reset request. No state changes on the aggregate — the one-time token
     * lives in Redis, not here — but the event belongs to the account, so it is raised here like
     * every other one. Invariant: only an ACTIVE account can reset a password, matching
     * {@link #changePassword}'s own guard on the other half of the flow.
     */
    public void requestPasswordReset(String resetToken, long expiresInMinutes) {
        if (status != AccountStatus.ACTIVE) {
            throw new InvalidAccountStatusException(
                    "Password reset can only be requested when ACTIVE, current: " + status);
        }
        domainEvents.add(new PasswordResetRequestedEvent(id, email, resetToken, expiresInMinutes));
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

    /**
     * Escalates an existing account to ADMIN and forces it ACTIVE, bypassing the normal status
     * state machine. Used only by the startup admin-bootstrap process (see
     * {@code AdminBootstrapRunner}) when the operator-designated email already has an account.
     */
    public void promoteToAdmin() {
        this.role = AccountRole.ADMIN;
        this.status = AccountStatus.ACTIVE;
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public AccountId getId()          { return id; }
    public Email getEmail()           { return email; }
    public HashedPassword getPassword() { return password; }
    public AccountStatus getStatus()  { return status; }
    public AccountRole getRole()      { return role; }
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
