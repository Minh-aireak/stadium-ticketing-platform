package com.aireak.identity.application.port.in;

/**
 * Inbound port: re-sends the email verification link for an account still awaiting verification.
 *
 * <p>The remedy for the two ways a customer ends up unable to activate that no amount of care in
 * {@code VerifyEmailService} can prevent: the welcome email never arrived, or its token outlived
 * its TTL before anyone clicked it. Without this the account is stranded — it cannot log in, and
 * registering the address again is refused.
 */
public interface ResendVerificationUseCase {

    void execute(String email);
}
