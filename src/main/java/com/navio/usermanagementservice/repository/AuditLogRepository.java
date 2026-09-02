package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Append-only audit access.
 *
 * <p>Only inserts and reads are exposed. The database trigger
 * {@code trg_iam_audit_log_append_only} rejects UPDATE and DELETE regardless, so
 * an accidental {@code deleteAll()} from inherited behaviour fails loudly
 * instead of destroying the trail.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);

    Page<AuditLog> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String resourceType, UUID resourceId, Pageable pageable);
}
