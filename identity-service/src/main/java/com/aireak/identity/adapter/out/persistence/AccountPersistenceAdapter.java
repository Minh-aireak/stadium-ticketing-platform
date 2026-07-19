package com.aireak.identity.adapter.out.persistence;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Persistence adapter implementing {@link AccountRepository} port.
 * Handles domain ↔ JPA entity mapping inline (simple enough not to need MapStruct here).
 */
@Component
@RequiredArgsConstructor
public class AccountPersistenceAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;

    @Override
    public void save(Account account) {
        jpaRepository.save(toJpaEntity(account));
    }

    @Override
    public Optional<Account> findById(AccountId accountId) {
        return jpaRepository.findById(accountId.value()).map(this::toDomain);
    }

    @Override
    public Optional<Account> findByEmail(Email email) {
        return jpaRepository.findByEmail(email.value()).map(this::toDomain);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return jpaRepository.existsByEmail(email.value());
    }

    // ---- Mapping ----

    private AccountJpaEntity toJpaEntity(Account account) {
        return AccountJpaEntity.builder()
                .id(account.getId().value())
                .email(account.getEmail().value())
                .passwordHash(account.getPassword().value())
                .status(account.getStatus())
                .registeredAt(account.getRegisteredAt())
                .build();
    }

    private Account toDomain(AccountJpaEntity entity) {
        return Account.reconstitute(
                new AccountId(entity.getId()),
                new Email(entity.getEmail()),
                new HashedPassword(entity.getPasswordHash()),
                entity.getStatus(),
                entity.getRegisteredAt()
        );
    }
}
