package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;

/**
 * Outbound port: password hashing (infrastructure concern).
 * Implemented by BCryptPasswordHashAdapter in adapter/out.
 * Kept outside domain to avoid coupling domain to BCrypt library.
 */
public interface PasswordHashPort {
    HashedPassword hash(RawPassword rawPassword);
    boolean matches(RawPassword rawPassword, HashedPassword hashedPassword);
}
