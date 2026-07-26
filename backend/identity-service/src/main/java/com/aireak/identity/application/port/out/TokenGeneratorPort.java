package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.Account;

/**
 * Outbound port: JWT token generation.
 * Implemented by JwtTokenGeneratorAdapter in adapter/out.
 */
public interface TokenGeneratorPort {
    /**
     * Generates a signed JWT for an authenticated account.
     * @return signed JWT string
     */
    String generateToken(Account account);
}
