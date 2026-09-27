package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.ModerationDtos.ModerationRequest;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ModerationConflictException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserBan;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Moderation carries the sharpest privilege rules in the service: who may act on
 * whom, and whether a suspension actually takes effect. Both are asserted here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserModerationServiceTest {

    private static final UUID MODERATOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    private static final String TARGET_SUBJECT = "kc-subject-target";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBanRepository userBanRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private BanStatusService banStatusService;

    @Mock
    private AuditService auditService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private PrivilegedActionGuard privilegedActionGuard;

    @InjectMocks
    private UserModerationService service;

    @Test
    void suspensionDisablesTheAccountAndRevokesLiveSessions() {
        givenTarget(List.of(NavioRole.USER));
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any())).thenReturn(List.of());
        when(userBanRepository.save(any(UserBan.class))).thenAnswer(call -> {
            UserBan ban = call.getArgument(0);
            ban.setId(UUID.randomUUID());
            return ban;
        });
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.suspend(moderator(), TARGET_ID, new ModerationRequest("Spam reports confirmed", null));

        verify(keycloakAdminClient).setUserEnabled(TARGET_SUBJECT, false);
        // Disabling alone leaves an unexpired refresh token usable, so sessions
        // must be revoked as well or the suspension is not actually effective.
        verify(keycloakAdminClient).logoutUser(TARGET_SUBJECT);
    }

    @Test
    void suspensionPublishesAnEventAndWritesAnAuditRecord() {
        givenTarget(List.of(NavioRole.USER));
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any())).thenReturn(List.of());
        when(userBanRepository.save(any(UserBan.class))).thenAnswer(call -> {
            UserBan ban = call.getArgument(0);
            ban.setId(UUID.randomUUID());
            return ban;
        });
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.suspend(moderator(), TARGET_ID, new ModerationRequest("Spam reports confirmed", null));

        verify(auditService).record(eq(MODERATOR_ID), eq(AuditAction.USER_SUSPENDED),
                anyString(), eq(TARGET_ID), any(), any(), any());
        verify(outboxService).publish(eq(TARGET_ID), eq(OutboxService.EVENT_USER_SUSPENDED), any());
    }

    @Test
    void moderatorCannotSuspendAnotherModerator() {
        // Otherwise one compromised moderator account could disable the people
        // able to revoke it.
        givenTarget(List.of(NavioRole.MODERATOR));

        assertThatThrownBy(() ->
                service.suspend(moderator(), TARGET_ID, new ModerationRequest("Abuse of power", null)))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), eq(false));
    }

    @Test
    void moderatorCannotSuspendAnAdmin() {
        givenTarget(List.of(NavioRole.ADMIN));

        assertThatThrownBy(() ->
                service.suspend(moderator(), TARGET_ID, new ModerationRequest("Disagreement", null)))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), eq(false));
    }

    @Test
    void evenAnAdministratorCannotSuspendAnOwner() {
        givenTarget(List.of(NavioRole.USER, NavioRole.OWNER));

        assertThatThrownBy(() ->
                service.suspend(admin(), TARGET_ID, new ModerationRequest("Policy violation", null)))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), eq(false));
    }

    @Test
    void adminMaySuspendAModerator() {
        givenTarget(List.of(NavioRole.MODERATOR));
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any())).thenReturn(List.of());
        when(userBanRepository.save(any(UserBan.class))).thenAnswer(call -> {
            UserBan ban = call.getArgument(0);
            ban.setId(UUID.randomUUID());
            return ban;
        });
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.suspend(admin(), TARGET_ID, new ModerationRequest("Policy violation", null));

        verify(keycloakAdminClient).setUserEnabled(TARGET_SUBJECT, false);
        // A privileged target must be serialized against the reverse action.
        verify(privilegedActionGuard).confirmAdministratorMayAct(admin(), TARGET_ID);
    }

    @Test
    void suspendingAnAdminIsRefusedWhenTheActingAdminLostStandingMeanwhile() {
        givenTarget(List.of(NavioRole.ADMIN));
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("Your account was suspended"))
                .when(privilegedActionGuard).confirmAdministratorMayAct(admin(), TARGET_ID);

        assertThatThrownBy(() ->
                service.suspend(admin(), TARGET_ID, new ModerationRequest("Crossing suspension", null)))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(userBanRepository, never()).save(any(UserBan.class));
        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), eq(false));
    }

    @Test
    void suspendingAnOrdinaryUserDoesNotTakeThePrivilegedLock() {
        givenTarget(List.of(NavioRole.USER));
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any())).thenReturn(List.of());
        when(userBanRepository.save(any(UserBan.class))).thenAnswer(call -> {
            UserBan ban = call.getArgument(0);
            ban.setId(UUID.randomUUID());
            return ban;
        });
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.suspend(moderator(), TARGET_ID, new ModerationRequest("Spam reports confirmed", null));

        org.mockito.Mockito.verifyNoInteractions(privilegedActionGuard);
    }

    @Test
    void nobodyCanSuspendThemselves() {
        User self = user(MODERATOR_ID, "kc-subject-moderator");
        when(userRepository.findByIdAndDeletedAtIsNull(MODERATOR_ID)).thenReturn(Optional.of(self));

        assertThatThrownBy(() ->
                service.suspend(moderator(), MODERATOR_ID, new ModerationRequest("Mistake", null)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void suspendingAnAlreadySuspendedAccountConflicts() {
        givenTarget(List.of(NavioRole.USER));
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any()))
                .thenReturn(List.of(UserBan.builder()
                        .id(UUID.randomUUID())
                        .userId(TARGET_ID)
                        .reason("Existing")
                        .bannedByUserId(ADMIN_ID)
                        .startsAt(Instant.now().minusSeconds(60))
                        .build()));

        assertThatThrownBy(() ->
                service.suspend(moderator(), TARGET_ID, new ModerationRequest("Duplicate", null)))
                .isInstanceOf(ModerationConflictException.class);
    }

    @Test
    void reactivatingAnAccountThatIsNotSuspendedConflicts() {
        givenTarget(List.of(NavioRole.USER));
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any())).thenReturn(List.of());

        assertThatThrownBy(() ->
                service.reactivate(moderator(), TARGET_ID, new ModerationRequest("Appeal upheld", null)))
                .isInstanceOf(ModerationConflictException.class);
    }

    @Test
    void reactivationClosesEveryActiveBan() {
        givenTarget(List.of(NavioRole.USER));
        UserBan first = ban();
        UserBan second = ban();
        when(userBanRepository.findActiveBans(eq(TARGET_ID), any())).thenReturn(List.of(first, second));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.reactivate(admin(), TARGET_ID, new ModerationRequest("Appeal upheld", null));

        // Leaving one open would keep the account locked out despite the
        // response saying it was reactivated.
        org.assertj.core.api.Assertions.assertThat(first.getRevokedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(second.getRevokedAt()).isNotNull();
        verify(keycloakAdminClient).setUserEnabled(TARGET_SUBJECT, true);
    }

    private void givenTarget(List<NavioRole> targetRoles) {
        when(userRepository.findByIdAndDeletedAtIsNull(TARGET_ID))
                .thenReturn(Optional.of(user(TARGET_ID, TARGET_SUBJECT)));
        when(keycloakAdminClient.realmRolesOf(TARGET_SUBJECT)).thenReturn(targetRoles);
    }

    private UserBan ban() {
        return UserBan.builder()
                .id(UUID.randomUUID())
                .userId(TARGET_ID)
                .reason("Original reason")
                .bannedByUserId(ADMIN_ID)
                .startsAt(Instant.now().minusSeconds(120))
                .createdAt(Instant.now().minusSeconds(120))
                .build();
    }

    private User user(UUID id, String subject) {
        return User.builder()
                .id(id)
                .authSubject(subject)
                .email("target@example.com")
                .displayName("Target")
                .status(UserStatus.ACTIVE)
                .preferences(new HashMap<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private AuthenticatedUser moderator() {
        return new AuthenticatedUser(MODERATOR_ID, "kc-subject-moderator", "mod@example.com", "Mod",
                Set.of(NavioRole.USER, NavioRole.MODERATOR));
    }

    private AuthenticatedUser admin() {
        return new AuthenticatedUser(ADMIN_ID, "kc-subject-admin", "admin@example.com", "Admin",
                Set.of(NavioRole.USER, NavioRole.ADMIN));
    }
}
