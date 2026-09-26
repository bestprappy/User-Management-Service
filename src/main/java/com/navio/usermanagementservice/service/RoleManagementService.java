package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.ModerationDtos.RoleAssignmentRequest;
import com.navio.usermanagementservice.dto.ModerationDtos.RoleAssignmentResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ModerationConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserRoleAssignment;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserRoleRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grants and revokes global Keycloak roles.
 *
 * <p>Keycloak performs the change; {@code iam.user_roles} is then updated as a
 * display snapshot. If Keycloak rejects the call the transaction rolls back and
 * the snapshot is never written, so the mirror cannot claim a privilege that was
 * not actually granted.
 *
 * <p>Callers reach this only through {@code ADMIN}-gated endpoints. The checks
 * below are the second layer, covering mistakes rather than missing roles.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleManagementService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final PrivilegedActionGuard privilegedActionGuard;

    @Transactional
    public RoleAssignmentResponse grant(AuthenticatedUser actor, UUID targetUserId, RoleAssignmentRequest request) {
        User target = requireUser(targetUserId);
        NavioRole role = request.role();

        List<NavioRole> currentRoles = keycloakAdminClient.realmRolesOf(target.getAuthSubject());
        if (currentRoles.contains(role)) {
            throw new ModerationConflictException("This user already has the " + role + " role");
        }

        keycloakAdminClient.grantRealmRole(target.getAuthSubject(), role);

        if (!userRoleRepository.existsByUserIdAndRole(target.getId(), role)) {
            userRoleRepository.save(UserRoleAssignment.builder()
                    .userId(target.getId())
                    .role(role)
                    .grantedByUserId(actor.id())
                    .grantedAt(Instant.now())
                    .build());
        }

        auditService.record(actor.id(), AuditAction.USER_ROLE_GRANTED,
                AuditAction.RESOURCE_USER, target.getId(),
                Map.of("roles", currentRoles.stream().map(Enum::name).toList()),
                Map.of("roles", withRole(currentRoles, role)),
                Map.of("reason", request.reason().trim(), "role", role.name()));

        outboxService.publish(target.getId(), OutboxService.EVENT_USER_ROLE_CHANGED, Map.of(
                "userId", target.getId().toString(),
                "role", role.name(),
                "change", "GRANTED"));

        log.info("Role {} granted to user {} by {}", role, target.getId(), actor.id());
        return new RoleAssignmentResponse(target.getId(),
                keycloakAdminClient.realmRolesOf(target.getAuthSubject()), Instant.now());
    }

    @Transactional
    public RoleAssignmentResponse revoke(AuthenticatedUser actor, UUID targetUserId, NavioRole role, String reason) {
        User target = requireUser(targetUserId);

        // An admin removing their own ADMIN role is the classic way to lock the
        // last administrator out of the realm. Refuse it; another admin can.
        if (actor.id().equals(target.getId()) && role == NavioRole.ADMIN) {
            throw new ForbiddenOperationException(
                    "You cannot revoke your own ADMIN role. Ask another administrator to do it.");
        }
        if (role == NavioRole.ADMIN) {
            // Two admins demoting each other at once would leave none.
            privilegedActionGuard.confirmAdministratorMayAct(actor, target.getId());
        }

        List<NavioRole> currentRoles = keycloakAdminClient.realmRolesOf(target.getAuthSubject());
        if (!currentRoles.contains(role)) {
            throw new ModerationConflictException("This user does not have the " + role + " role");
        }

        keycloakAdminClient.revokeRealmRole(target.getAuthSubject(), role);
        userRoleRepository.deleteByUserIdAndRole(target.getId(), role);

        auditService.record(actor.id(), AuditAction.USER_ROLE_REVOKED,
                AuditAction.RESOURCE_USER, target.getId(),
                Map.of("roles", currentRoles.stream().map(Enum::name).toList()),
                Map.of("roles", withoutRole(currentRoles, role)),
                Map.of("reason", reason == null ? "" : reason.trim(), "role", role.name()));

        outboxService.publish(target.getId(), OutboxService.EVENT_USER_ROLE_CHANGED, Map.of(
                "userId", target.getId().toString(),
                "role", role.name(),
                "change", "REVOKED"));

        log.info("Role {} revoked from user {} by {}", role, target.getId(), actor.id());
        return new RoleAssignmentResponse(target.getId(),
                keycloakAdminClient.realmRolesOf(target.getAuthSubject()), Instant.now());
    }

    /** Current roles for a user, read from Keycloak rather than the snapshot. */
    @Transactional(readOnly = true)
    public List<NavioRole> rolesOf(UUID userId) {
        User target = requireUser(userId);
        return keycloakAdminClient.realmRolesOf(target.getAuthSubject());
    }

    private User requireUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private List<String> withRole(List<NavioRole> roles, NavioRole added) {
        return java.util.stream.Stream.concat(roles.stream(), java.util.stream.Stream.of(added))
                .distinct()
                .map(Enum::name)
                .toList();
    }

    private List<String> withoutRole(List<NavioRole> roles, NavioRole removed) {
        return roles.stream().filter(role -> role != removed).map(Enum::name).toList();
    }
}
