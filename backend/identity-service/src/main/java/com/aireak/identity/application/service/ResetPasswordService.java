package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.ResetPasswordUseCase;
import com.aireak.identity.application.port.in.command.ResetPasswordCommand;
import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Application service: consumes a one-time password reset token and sets the new password.
 *
 * <p>The new password is validated against the current policy ({@link RawPassword}) <em>before</em>
 * the token is touched, so a rejected password doesn't burn the customer's only reset link.
 *
 * <p>Every refresh session the account has is revoked once the new password is stored. A reset is
 * the customer's remedy for an account they believe is compromised, and it is worth nothing if the
 * intruder's existing session keeps refreshing itself past the change. Already-issued access
 * tokens still run to their own short expiry — they are stateless by design and nothing here can
 * recall them — but no new one can be minted after this point without the new password.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}; only {@link PasswordResetSteps} is.
 * Two of the three calls below talk to Redis, and holding a database connection across them bought
 * nothing — a Redis write is not rolled back by a Postgres rollback, so the transaction never made
 * the pair atomic, it only made the connection unavailable for as long as Redis took to answer.
 *
 * <p>Order matters, and the failure at each point is survivable: a revoke that fails leaves the
 * token unspent, so the customer's link still works and a retry re-runs both steps (setting the
 * same password again is harmless). Spending the token last is what makes that retry possible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResetPasswordService implements ResetPasswordUseCase {

    private final PasswordResetTokenPort resetTokenPort;
    private final RefreshSessionStorePort refreshSessionStore;
    private final PasswordResetSteps resetSteps;

    @Override
    public void execute(ResetPasswordCommand command) {
        RawPassword newPassword = new RawPassword(command.newPassword());

        AccountId accountId = resetTokenPort.peek(command.token());

        resetSteps.changePassword(accountId, newPassword);
        refreshSessionStore.revokeAllForAccount(accountId);
        resetTokenPort.invalidate(command.token());

        log.info("Password reset completed: id={}", accountId);
    }
}
