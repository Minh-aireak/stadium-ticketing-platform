package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.common.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "matches")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "match_id", nullable = false, length = 36)
    private String matchId;

    @Column(name = "home_team", nullable = false, length = 100)
    private String homeTeam;

    @Column(name = "away_team", nullable = false, length = 100)
    private String awayTeam;

    @Column(name = "competition", nullable = false, length = 100)
    private String competition;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MatchStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", referencedColumnName = "match_id",
                foreignKey = @ForeignKey(name = "fk_showtimes_match"))
    @Builder.Default
    private List<ShowtimeJpaEntity> showtimes = new ArrayList<>();
}
