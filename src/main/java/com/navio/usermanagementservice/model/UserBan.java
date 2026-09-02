package com.navio.usermanagementservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A suspension record. Creating one must also disable the Keycloak account;
 * revoking one must re-enable it.
 *
 * <p>A user is suspended when a row exists with {@code revoked_at IS NULL} and
 * {@code ends_at IS NULL OR ends_at > now()}. History is kept rather than
 * overwritten so repeat offences remain visible to moderators.
 */
@Entity
@Table(name = "user_bans", schema = "iam")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "banned_by_user_id", nullable = false, updatable = false)
    private UUID bannedByUserId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** {@code null} means the suspension does not expire on its own. */
    @Column(name = "ends_at")
    private Instant endsAt;

    /** Set when a moderator lifts the suspension early. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        if (startsAt == null) {
            startsAt = now;
        }
    }

    /** True while this record still withholds access at {@code at}. */
    public boolean isActiveAt(Instant at) {
        return revokedAt == null
                && !startsAt.isAfter(at)
                && (endsAt == null || endsAt.isAfter(at));
    }
}
