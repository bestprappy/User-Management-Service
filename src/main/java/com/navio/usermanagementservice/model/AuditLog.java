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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only record of a sensitive operation.
 *
 * <p>Marked {@link Immutable} so Hibernate will not emit an UPDATE, and backed by
 * a database trigger that rejects UPDATE and DELETE outright. Defence in depth:
 * the application cannot rewrite history even if a service bug tries to, and a
 * stolen application credential cannot erase the evidence of its own use.
 *
 * <p>Payload columns must never receive secrets — no tokens, no passwords, no
 * client secrets. Diffs carry field-level profile state only.
 */
@Entity
@Table(name = "audit_log", schema = "iam")
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null for system-initiated actions such as scheduled ban expiry. */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "resource_type", updatable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false, columnDefinition = "text")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_jsonb", updatable = false)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_jsonb", updatable = false)
    private Map<String, Object> after;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_jsonb", nullable = false, updatable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (metadata == null) {
            metadata = new HashMap<>();
        }
    }
}
