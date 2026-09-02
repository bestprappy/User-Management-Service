package com.navio.usermanagementservice.model;

import com.navio.usermanagementservice.security.NavioRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Display/audit snapshot of a Keycloak global role grant.
 *
 * <p><strong>Not an authorization source.</strong> Access decisions read roles
 * from the validated JWT; this table is written after Keycloak confirms a change
 * so the UI and audit trail have something to show without calling the Admin API
 * on every page load. If the two ever disagree, Keycloak wins.
 */
@Entity
@Table(name = "user_roles", schema = "iam")
@IdClass(UserRoleAssignment.UserRoleId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRoleAssignment {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NavioRole role;

    @Column(name = "granted_by_user_id")
    private UUID grantedByUserId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @PrePersist
    protected void onCreate() {
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }

    /** Composite primary key {@code (user_id, role)}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRoleId implements java.io.Serializable {

        private UUID userId;
        private NavioRole role;
    }
}
