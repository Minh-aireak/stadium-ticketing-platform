package com.aireak.identity.application.port.in;

import com.aireak.identity.application.port.in.command.ResetPasswordCommand;

/** Inbound port: consumes a one-time reset token and sets the account's new password. */
public interface ResetPasswordUseCase {

    /**
     * @throws com.aireak.identity.domain.exception.InvalidPasswordResetTokenException token is
     *         missing, unknown, or expired
     * @throws com.aireak.identity.domain.exception.InvalidPasswordException the new password does
     *         not satisfy the password policy
     */
    void execute(ResetPasswordCommand command);
}
