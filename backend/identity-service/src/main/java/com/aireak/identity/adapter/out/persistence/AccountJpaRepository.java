package com.aireak.identity.adapter.out.persistence;

import com.aireak.identity.domain.model.AccountRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository — internal to adapter layer. */
interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {
    Optional<AccountJpaEntity> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByRole(AccountRole role);
}
