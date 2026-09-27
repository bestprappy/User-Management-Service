package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.ModerationDtos.ActiveSuspension;
import com.navio.usermanagementservice.dto.ModerationDtos.AdminStatisticsResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.AdminUserDetailResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.AdminUserSummaryResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.ModerationEventResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminException;
import com.navio.usermanagementservice.model.AuditLog;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserBan;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.AuditLogRepository;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserRoleRepository;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-side support for the moderation console.
 *
 * <p>Role data in the search listing comes from the {@code iam.user_roles}
 * snapshot rather than Keycloak. A listing of 50 users would otherwise mean 50
 * Admin API round trips per page. The snapshot is adequate for a list view; the
 * single-user detail and anything that acts on a role re-read it from Keycloak.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminUserService {

    /** Window for the "joined recently" count. */
    static final Duration JOINED_WINDOW = Duration.ofDays(30);

    /** Only the actions a moderator needs to see in an account's history. */
    static final Set<String> MODERATION_ACTIONS = Set.of(
            AuditAction.USER_SUSPENDED,
            AuditAction.USER_REACTIVATED,
            AuditAction.USER_ROLE_GRANTED,
            AuditAction.USER_ROLE_REVOKED);

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserBanRepository userBanRepository;
    private final AuditLogRepository auditLogRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final Clock clock;

    /**
     * Searches users by email or display name.
     *
     * @param term   free text; may be null to list everyone.
     * @param status optional status filter.
     */
    public Page<AdminUserSummaryResponse> search(String term, UserStatus status, Pageable pageable) {
        String normalizedTerm = (term == null || term.isBlank()) ? null : term.trim();
        Page<User> page;
        if (normalizedTerm == null && status == null) {
            page = userRepository.findAllBy(pageable);
        } else if (normalizedTerm == null) {
            page = userRepository.findByStatus(status, pageable);
        } else if (status == null) {
            page = userRepository.searchByTerm(normalizedTerm, pageable);
        } else {
            page = userRepository.searchByTermAndStatus(normalizedTerm, status, pageable);
        }

        // Roles and bans are fetched once for the whole page rather than per row,
        // so a large page does not turn into 2N queries.
        Set<UUID> userIds = page.getContent().stream().map(User::getId).collect(Collectors.toSet());
        Map<UUID, List<NavioRole>> rolesByUser = rolesFor(userIds);

        Instant now = clock.instant();
        return page.map(user -> new AdminUserSummaryResponse(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getStatus().value(),
                rolesByUser.getOrDefault(user.getId(), List.of()),
                user.getCreatedAt(),
                suspendedUntil(user.getId(), now)
        ));
    }

    /**
     * Account counts for the dashboard.
     *
     * <p>Deleted profiles are excluded from every figure. Each count is a single
     * indexed aggregate, so the cost does not grow with page size or traffic.
     */
    public AdminStatisticsResponse statistics() {
        Instant asOf = clock.instant();
        Instant joinedSince = asOf.minus(JOINED_WINDOW);
        return new AdminStatisticsResponse(
                userRepository.countByDeletedAtIsNullAndStatusNot(UserStatus.DELETED),
                userRepository.countByDeletedAtIsNullAndStatus(UserStatus.ACTIVE),
                userRepository.countByDeletedAtIsNullAndStatus(UserStatus.SUSPENDED),
                userRepository.countByDeletedAtIsNullAndStatusNotAndCreatedAtGreaterThanEqual(
                        UserStatus.DELETED, joinedSince),
                joinedSince,
                asOf);
    }

    /**
     * One account, including soft-deleted ones: an investigation often concerns
     * exactly the account that was deleted.
     */
    public AdminUserDetailResponse detail(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        List<NavioRole> roles;
        boolean rolesVerified;
        try {
            roles = keycloakAdminClient.realmRolesOf(user.getAuthSubject());
            rolesVerified = true;
        } catch (KeycloakAdminException ex) {
            // The drawer is still useful without live roles; say so rather than
            // failing the whole view. Moderation actions re-check Keycloak anyway.
            log.warn("Could not read roles from Keycloak for user {}; using the local snapshot", userId);
            roles = rolesFor(Set.of(userId)).getOrDefault(userId, List.of());
            rolesVerified = false;
        }

        ActiveSuspension suspension = userBanRepository.findActiveBans(userId, clock.instant()).stream()
                .findFirst()
                .map(this::toActiveSuspension)
                .orElse(null);

        return new AdminUserDetailResponse(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getStatus().value(),
                roles,
                rolesVerified,
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getDeletedAt(),
                suspension);
    }

    /** Suspensions, reactivations and role changes applied to one account, newest first. */
    public Page<ModerationEventResponse> moderationEvents(UUID userId, Pageable pageable) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        Page<AuditLog> events = auditLogRepository.findByResourceTypeAndResourceIdAndActionInOrderByCreatedAtDesc(
                AuditAction.RESOURCE_USER, userId, MODERATION_ACTIONS, pageable);

        Set<UUID> actorIds = events.getContent().stream()
                .map(AuditLog::getActorUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> actorNames = displayNames(actorIds);

        return events.map(event -> new ModerationEventResponse(
                event.getId(),
                event.getAction(),
                event.getActorUserId(),
                event.getActorUserId() == null ? null : actorNames.get(event.getActorUserId()),
                metadataText(event, "reason"),
                metadataText(event, "role"),
                event.getCreatedAt()));
    }

    private ActiveSuspension toActiveSuspension(UserBan ban) {
        String bannedBy = displayNames(Set.of(ban.getBannedByUserId())).get(ban.getBannedByUserId());
        return new ActiveSuspension(ban.getReason(), ban.getStartsAt(), ban.getEndsAt(),
                ban.getBannedByUserId(), bannedBy);
    }

    private Map<UUID, String> displayNames(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
    }

    private static String metadataText(AuditLog event, String key) {
        Object value = event.getMetadata() == null ? null : event.getMetadata().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private Map<UUID, List<NavioRole>> rolesFor(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> userRoleRepository.findByUserId(userId).stream()
                        .map(assignment -> assignment.getRole())
                        .toList()));
    }

    /**
     * @return when the current suspension ends, or null if not suspended or the
     * suspension is indefinite.
     */
    private Instant suspendedUntil(UUID userId, Instant now) {
        return userBanRepository.findActiveBans(userId, now).stream()
                .findFirst()
                .map(ban -> ban.getEndsAt())
                .orElse(null);
    }
}
