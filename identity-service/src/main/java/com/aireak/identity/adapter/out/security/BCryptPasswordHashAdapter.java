package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter: BCrypt password hashing.
 * Implements {@link PasswordHashPort} — the only class that touches Spring Security's
 * {@link PasswordEncoder}. Domain has no BCrypt dependency.
 */
@Component
@RequiredArgsConstructor
public class BCryptPasswordHashAdapter implements PasswordHashPort {

    private final PasswordEncoder passwordEncoder;

    @Override
    public HashedPassword hash(RawPassword rawPassword) {
        return new HashedPassword(passwordEncoder.encode(rawPassword.exposeForHashing()));
    }

    @Override
    public boolean matches(RawPassword rawPassword, HashedPassword hashedPassword) {
        return passwordEncoder.matches(rawPassword.exposeForHashing(), hashedPassword.value());
    }
}
