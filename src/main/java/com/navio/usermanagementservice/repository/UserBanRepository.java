package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.UserBan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserBanRepository extends JpaRepository<UserBan, UUID> {

    /**
     * The active-suspension predicate from the schema rules: not revoked, already
     * started, and either open-ended or not yet expired.
     *
     * <p>Evaluated with a caller-supplied {@code now} rather than {@code now()} so
     * the same logic is testable at a fixed instant.
     */
    @Query("""
            SELECT b FROM UserBan b
             WHERE b.userId = :userId
               AND b.revokedAt IS NULL
               AND b.startsAt <= :now
               AND (b.endsAt IS NULL OR b.endsAt > :now)
             ORDER BY b.startsAt DESC
            """)
    List<UserBan> findActiveBans(@Param("userId") UUID userId, @Param("now") Instant now);

    List<UserBan> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
