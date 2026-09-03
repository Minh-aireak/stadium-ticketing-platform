package com.aireak.identity.domain.model;

import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
     * Raises {@link AccountRegisteredEvent} again with a freshly minted verification token, so the
     * welcome email — the only thing that carries a verification link — is sent a second time.
     *
     * <p>Reuses the registration event rather than introducing a topic of its own: what the
     * customer needs is the same message with a working link, notification-service already turns
     * that event into exactly that, and the event id differs each time so its idempotency store
     * does not swallow the resend.
     *
     * <p>Refuses anything already ACTIVE. Sending a verification link to a verified account is at
     * best noise and at worst a link that activates nothing.
     */
    public void reissueVerification(String verificationToken) {
        if (status != AccountStatus.PENDING_VERIFICATION) {
            throw new InvalidAccountStatusException(
                    "Verification can only be resent while PENDING_VERIFICATION, current: " + status);
        }
        domainEvents.add(new AccountRegisteredEvent(id, email, verificationToken));
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
     * Escalates an existing account to ADMIN, forces it ACTIVE, and replaces its password with the
     * operator-supplied one, bypassing the normal status state machine. Used only by the startup
     * admin-bootstrap process (see {@code AdminBootstrapRunner}) when the operator-designated
     * email already has an account.
     *
     * <p>The password is part of the promotion, not an optional extra. The bootstrap's guard is
     * "no ADMIN exists yet", not "no account exists yet", and registration is public — so the
     * account found at the operator's chosen address may be one somebody else created there
     * first. Leaving that account's own password in place would make this method mean "hand ADMIN
     * to whoever registered this address first", and the forced ACTIVE below would finish the job
     * by waiving the email verification that is the only proof they control the mailbox. Taking
     * the password too is what makes promoting an existing account end in the same state as
     * {@link #registerAdmin} creating a fresh one: the credential the operator supplied is the
     * credential that opens it.
     */
    public void promoteToAdmin(HashedPassword newPassword) {
        this.password = Objects.requireNonNull(newPassword, "Bootstrap password must not be null");
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
