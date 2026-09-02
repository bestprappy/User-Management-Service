package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.ModerationDtos.ModerationRequest;
import com.navio.usermanagementservice.dto.ModerationDtos.ModerationResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ModerationConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserBan;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account suspension and reactivation.
 *
 * <h2>Ordering, and why it is this way round</h2>
 * The local write happens first, then the Keycloak call, both inside one
 * transaction:
 * <ol>
 *   <li>If Keycloak rejects the change, the transaction rolls back and nothing
 *       moved.</li>
 *   <li>If the commit fails after Keycloak succeeded, the account is disabled in
 *       Keycloak while the local row is unchanged — the user is locked out, not
 *       let in. Failing closed is the acceptable direction for a suspension.</li>
 * </ol>
 *
 * <h2>Making a suspension take effect immediately</h2>
 * Disabling an account only stops <em>new</em> tokens being issued. A suspension
 * therefore also revokes every active session (invalidating refresh tokens) and
 * evicts this service's ban cache, so the already-issued access token stops
 * working here on the very next request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserModerationService {

    private final UserRepository userRepository;
    private final UserBanRepository userBanRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final BanStatusService banStatusService;
    private final AuditService auditService;
    private final OutboxService outboxService;

    @Transactional
    public ModerationResponse suspend(AuthenticatedUser actor, UUID targetUserId, ModerationRequest request) {
        User target = requireUser(targetUserId);
        assertMayModerate(actor, target);

        if (!userBanRepository.findActiveBans(targetUserId, Instant.now()).isEmpty()) {
            throw new ModerationConflictException("This account is already suspended");
        }

        UserBan ban = userBanRepository.save(UserBan.builder()
                .userId(target.getId())
                .reason(request.reason().trim())
                .bannedByUserId(actor.id())
                .startsAt(Instant.now())
                .endsAt(request.expiresAt())
                .build());

        UserStatus previousStatus = target.getStatus();
        target.setStatus(UserStatus.SUSPENDED);
        User saved = userRepository.save(target);

        auditService.record(actor.id(), AuditAction.USER_SUSPENDED,
                AuditAction.RESOURCE_USER, target.getId(),
                Map.of("status", previousStatus.value()),
                Map.of("status", UserStatus.SUSPENDED.value()),
                metadata(request.reason(), request.expiresAt(), ban.getId()));

        outboxService.publish(target.getId(), OutboxService.EVENT_USER_SUSPENDED, Map.of(
                "userId", target.getId().toString(),
                "reason", request.reason().trim(),
                "expiresAt", request.expiresAt() == null ? "" : request.expiresAt().toString()));

        // Inside the transaction: a Keycloak failure must roll the local change back.
        keycloakAdminClient.setUserEnabled(target.getAuthSubject(), false);
        // Kill live sessions so an unexpired refresh token cannot mint new access
        // tokens. Without this, disabling alone leaves a usable credential.
        keycloakAdminClient.logoutUser(target.getAuthSubject());

        evictBanCacheAfterCommit(target.getId());

        log.info("User {} suspended by {}", target.getId(), actor.id());
        return new ModerationResponse(
                saved.getId(), UserStatus.SUSPENDED.value(),
                ban.getReason(), ban.getEndsAt(), saved.getUpdatedAt());
    }

    @Transactional
    public ModerationResponse reactivate(AuthenticatedUser actor, UUID targetUserId, ModerationRequest request) {
        User target = requireUser(targetUserId);
        assertMayModerate(actor, target);

        List<UserBan> activeBans = userBanRepository.findActiveBans(targetUserId, Instant.now());
        if (activeBans.isEmpty()) {
            throw new ModerationConflictException("This account is not suspended");
        }

        Instant now = Instant.now();
        // Close every active ban, not just the newest: leaving one open would
        // keep the account locked out despite a "reactivated" response.
        activeBans.forEach(ban -> ban.setRevokedAt(now));
        userBanRepository.saveAll(activeBans);

        UserStatus previousStatus = target.getStatus();
        target.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(target);

        auditService.record(actor.id(), AuditAction.USER_REACTIVATED,
                AuditAction.RESOURCE_USER, target.getId(),
                Map.of("status", previousStatus.value()),
                Map.of("status", UserStatus.ACTIVE.value()),
                metadata(request.reason(), null, null));

        outboxService.publish(target.getId(), OutboxService.EVENT_USER_REACTIVATED, Map.of(
                "userId", target.getId().toString(),
                "reason", request.reason() == null ? "" : request.reason().trim()));

        keycloakAdminClient.setUserEnabled(target.getAuthSubject(), true);

        evictBanCacheAfterCommit(target.getId());

        log.info("User {} reactivated by {}", target.getId(), actor.id());
        return new ModerationResponse(
                saved.getId(), UserStatus.ACTIVE.value(),
                request.reason(), null, saved.getUpdatedAt());
    }

    /**
     * Enforces who may act on whom.
     *
     * <p>Three rules, each closing a specific failure:
     * <ul>
     *   <li>No self-moderation — a moderator locking themselves out is an
     *       operational incident, not a moderation decision.</li>
     *   <li>A moderator cannot suspend another moderator or an admin. Otherwise a
     *       single compromised moderator account could disable the very people
     *       able to revoke it.</li>
     *   <li>Only an admin may act on a privileged account.</li>
     * </ul>
     *
     * <p>The target's roles are read from Keycloak, the authoritative source,
     * rather than the {@code iam.user_roles} snapshot which may lag.
     */
    private void assertMayModerate(AuthenticatedUser actor, User target) {
        if (actor.id().equals(target.getId())) {
            throw new ForbiddenOperationException("You cannot moderate your own account");
        }

        List<NavioRole> targetRoles = keycloakAdminClient.realmRolesOf(target.getAuthSubject());
        boolean targetIsPrivileged =
                targetRoles.contains(NavioRole.ADMIN) || targetRoles.contains(NavioRole.MODERATOR);

        if (targetIsPrivileged && !actor.isAdmin()) {
            throw new ForbiddenOperationException(
                    "Only an administrator can moderate a moderator or administrator account");
        }
    }

    private User requireUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /**
     * Evicts the ban cache only once the transaction commits.
     *
     * <p>Evicting eagerly would let a concurrent request re-populate the cache
     * from the pre-commit state, leaving a suspended user cached as active until
     * the TTL expired.
     */
    private void evictBanCacheAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            banStatusService.evict(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                banStatusService.evict(userId);
            }
        });
    }

    private Map<String, Object> metadata(String reason, Instant expiresAt, UUID banId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", reason == null ? null : reason.trim());
        if (expiresAt != null) {
            metadata.put("expiresAt", expiresAt.toString());
        }
        if (banId != null) {
            metadata.put("banId", banId.toString());
        }
        return metadata;
    }
}
