package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.dto.ModerationDtos.RoleAssignmentRequest;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserRoleRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleManagementServiceTests {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final String TARGET_SUBJECT = "kc-subject-target";

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;
    @Mock private AuditService auditService;
    @Mock private OutboxService outboxService;
    @Mock private PrivilegedActionGuard privilegedActionGuard;
    @InjectMocks private RoleManagementService service;

    private final AuthenticatedUser admin = new AuthenticatedUser(ADMIN_ID, "kc-subject-admin",
            "admin@example.com", "Admin", Set.of(NavioRole.ADMIN));
    private final AuthenticatedUser owner = new AuthenticatedUser(ADMIN_ID, "kc-subject-admin",
            "admin@example.com", "Owner", Set.of(NavioRole.OWNER));

    @BeforeEach
    void setUp() {
        when(keycloakAdminClient.realmRolesOf("kc-subject-admin"))
                .thenReturn(List.of(NavioRole.ADMIN, NavioRole.OWNER));
    }

    @Test
    void revokingAnotherAdminsRoleIsSerializedAgainstTheReverseAction() {
        givenTarget(List.of(NavioRole.USER, NavioRole.ADMIN));

        service.revoke(owner, TARGET_ID, NavioRole.ADMIN, "Left the team");

        verify(privilegedActionGuard).confirmAdministratorMayAct(owner, TARGET_ID);
        verify(keycloakAdminClient).revokeRealmRole(TARGET_SUBJECT, NavioRole.ADMIN);
    }

    @Test
    void crossingAdminDemotionIsRefusedBeforeKeycloakChanges() {
        givenTarget(List.of(NavioRole.USER, NavioRole.ADMIN));
        doThrow(new ForbiddenOperationException("Your administrator role was removed"))
                .when(privilegedActionGuard).confirmAdministratorMayAct(owner, TARGET_ID);

        assertThatThrownBy(() -> service.revoke(owner, TARGET_ID, NavioRole.ADMIN, "Crossing demotion"))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
    }

    @Test
    void revokingTheModeratorRoleChecksLivePrivileges() {
        givenTarget(List.of(NavioRole.USER, NavioRole.MODERATOR));

        service.revoke(admin, TARGET_ID, NavioRole.MODERATOR, "Stepped down");

        verify(privilegedActionGuard).confirmAdministratorMayAct(admin, TARGET_ID);
    }

    @Test
    void administratorCannotGrantOrRevokeAdministratorRole() {
        givenTarget(List.of(NavioRole.USER, NavioRole.ADMIN));
        when(keycloakAdminClient.realmRolesOf("kc-subject-admin")).thenReturn(List.of(NavioRole.ADMIN));

        assertThatThrownBy(() -> service.grant(admin, TARGET_ID,
                new RoleAssignmentRequest(NavioRole.ADMIN, "New staff lead")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.revoke(admin, TARGET_ID, NavioRole.ADMIN, "Team change"))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(keycloakAdminClient, never()).grantRealmRole(any(), any());
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
    }

    @Test
    void ownerCanGrantAdministratorRoleButNobodyCanGrantOwnerThroughApi() {
        givenTarget(List.of(NavioRole.USER));

        service.grant(owner, TARGET_ID, new RoleAssignmentRequest(NavioRole.ADMIN, "New administrator"));
        verify(keycloakAdminClient).grantRealmRole(TARGET_SUBJECT, NavioRole.ADMIN);

        assertThatThrownBy(() -> service.grant(owner, TARGET_ID,
                new RoleAssignmentRequest(NavioRole.OWNER, "New owner")))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(keycloakAdminClient, never()).grantRealmRole(TARGET_SUBJECT, NavioRole.OWNER);
    }

    @Test
    void administratorCanGrantModeratorButAStaleAdminTokenCannot() {
        givenTarget(List.of(NavioRole.USER));
        when(keycloakAdminClient.realmRolesOf("kc-subject-admin")).thenReturn(List.of(NavioRole.ADMIN));

        service.grant(admin, TARGET_ID, new RoleAssignmentRequest(NavioRole.MODERATOR, "Community reports"));
        verify(keycloakAdminClient).grantRealmRole(TARGET_SUBJECT, NavioRole.MODERATOR);

        when(keycloakAdminClient.realmRolesOf("kc-subject-admin")).thenReturn(List.of(NavioRole.USER));
        assertThatThrownBy(() -> service.grant(admin, TARGET_ID,
                new RoleAssignmentRequest(NavioRole.MODERATOR, "Second request")))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(keycloakAdminClient, org.mockito.Mockito.times(1))
                .grantRealmRole(TARGET_SUBJECT, NavioRole.MODERATOR);
    }

    @Test
    void ownerAccountCannotBeChangedThroughApi() {
        givenTarget(List.of(NavioRole.USER, NavioRole.OWNER));

        assertThatThrownBy(() -> service.revoke(admin, TARGET_ID, NavioRole.MODERATOR, "Changes"))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
    }

    @Test
    void anAdminStillCannotRevokeTheirOwnAdminRole() {
        when(userRepository.findByIdAndDeletedAtIsNull(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, "kc-subject-admin")));

        assertThatThrownBy(() -> service.revoke(admin, ADMIN_ID, NavioRole.ADMIN, "Oops"))
                .isInstanceOf(ForbiddenOperationException.class);

        verifyNoInteractions(privilegedActionGuard);
        verify(keycloakAdminClient, never()).revokeRealmRole(any(), any());
    }

    private void givenTarget(List<NavioRole> roles) {
        when(userRepository.findByIdAndDeletedAtIsNull(TARGET_ID)).thenReturn(Optional.of(user(TARGET_ID, TARGET_SUBJECT)));
        when(keycloakAdminClient.realmRolesOf(TARGET_SUBJECT)).thenReturn(roles);
    }

    private User user(UUID id, String subject) {
        return User.builder().id(id).authSubject(subject).email("target@example.com").displayName("Target")
                .status(UserStatus.ACTIVE).preferences(new HashMap<>())
                .createdAt(Instant.parse("2026-09-01T00:00:00Z")).updatedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build();
    }
}
