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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox row for the {@code user.events.v1} topic.
 *
 * <p>Written inside the same transaction as the state change it describes. A
 * relay publishes it afterwards. This matters for security-relevant events:
 * without it, "user suspended" could commit locally while the Kafka publish
 * fails, leaving other services enforcing a stale, permissive view of the
 * account.
 */
@Entity
@Table(name = "outbox", schema = "iam")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Example: {@code UserSuspended.v1}. */
    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_jsonb", nullable = false, updatable = false)
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the relay confirms the broker accepted the record. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (payload == null) {
            payload = new HashMap<>();
        }
    }
}
