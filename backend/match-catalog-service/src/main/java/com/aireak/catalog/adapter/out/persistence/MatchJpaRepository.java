package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.domain.model.MatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface MatchJpaRepository extends JpaRepository<MatchJpaEntity, String> {
    Page<MatchJpaEntity> findByStatus(MatchStatus status, Pageable pageable);
    long countByStatus(MatchStatus status);
}
