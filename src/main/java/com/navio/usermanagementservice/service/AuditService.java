package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.model.AuditLog;
import com.navio.usermanagementservice.repository.AuditLogRepository;
import com.navio.usermanagementservice.security.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the append-only security audit trail.
 *
 * <p>Audit rows for a successful action are written in the <em>same</em>
 * transaction as the action itself. If the action rolls back, its audit entry
 * must roll back with it — a trail claiming a suspension that never took effect
 * is worse than no trail.
 *
 * <p>{@link #recordRejectedAttempt} is the exception: it runs in its own
 * transaction because the surrounding request is about to fail, and the record
 * of the refused attempt must survive that failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final RequestContext requestContext;

    /**
     * Records a completed privileged action.
     *
     * @param actorUserId  who acted; null for system-initiated changes.
     * @param action       one of {@link AuditAction}.
     * @param resourceType logical type of the affected resource.
     * @param resourceId   affected resource id.
     * @param before       prior state; must contain no secrets.
     * @param after        new state; must contain no secrets.
     * @param metadata     extra context such as the moderator's stated reason.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId,
                       String action,
                       String resourceType,
                       UUID resourceId,
                       Map<String, Object> before,
                       Map<String, Object> after,
                       Map<String, Object> metadata) {
        auditLogRepository.save(build(actorUserId, action, resourceType, resourceId, before, after, metadata));
    }

    /** Convenience overload for actions with no meaningful state diff. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId, String action, String resourceType, UUID resourceId,
                       Map<String, Object> metadata) {
        record(actorUserId, action, resourceType, resourceId, null, null, metadata);
    }

    /**
     * Records an action that was refused.
     *
     * <p>Runs in a new transaction so it persists even though the caller's
     * transaction is about to roll back. Repeated rows here are the signal that
     * someone is probing with a suspended or stolen credential.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejectedAttempt(UUID actorUserId, String action, Map<String, Object> metadata) {
        try {
            auditLogRepository.save(
                    build(actorUserId, action, AuditAction.RESOURCE_USER, actorUserId, null, null, metadata));
        } catch (RuntimeException exception) {
            // Never let an audit write failure mask the original rejection.
            log.error("Failed to record rejected attempt '{}' for actor {}", action, actorUserId, exception);
        }
    }

    private AuditLog build(UUID actorUserId,
                           String action,
                           String resourceType,
                           UUID resourceId,
                           Map<String, Object> before,
                           Map<String, Object> after,
                           Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        return AuditLog.builder()
                .actorUserId(actorUserId)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .ipAddress(requestContext.clientIp().orElse(null))
                .userAgent(requestContext.userAgent().orElse(null))
                .before(before)
                .after(after)
                .metadata(safeMetadata)
                .build();
    }
}
