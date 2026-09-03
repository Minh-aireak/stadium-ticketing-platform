package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.Email;

/**
 * Outbound port: counts recent failed logins for one email address, so a password can be attacked
 * only so fast no matter how many source addresses the attacker has.
 *
 * <p>This is deliberately not an account lock. Nothing here changes {@code AccountStatus} and
 * nothing needs an administrator to undo it: the count expires on its own, and a successful login
 * clears it. A lock keyed on something an attacker chooses — and the email in a login request is
 * exactly that — hands them a way to shut a real user out of their own account for as long as they
 * care to keep asking.
 *
 * <p>Keyed on the submitted email whether or not it resolves to an account. Throttling only known
 * emails would make the throttle itself answer the question the rest of {@code LoginService} works
 * to keep quiet (see its dummy-hash comparison): an attacker would learn an address is registered
 * by watching which addresses can be throttled.
 *
 * <p>Implementations must fail open. Login is not the place to convert a Redis outage into an
 * outage of its own — the gateway's per-IP limit still stands, which is what this supplements.
 */
public interface LoginAttemptLimiterPort {

    /** Whether {@code email} has failed enough times recently that further attempts are refused. */
    boolean isThrottled(Email email);

    /** Records one failed attempt, extending the window it is counted in. */
    void recordFailure(Email email);

    /** Clears the count after a successful login. */
    void reset(Email email);
}
