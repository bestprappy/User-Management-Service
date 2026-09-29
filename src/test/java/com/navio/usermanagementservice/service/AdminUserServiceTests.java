package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.ModerationDtos.AdminStatisticsResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.AdminUserDetailResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.ModerationEventResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminException;
import com.navio.usermanagementservice.model.AuditLog;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserBan;
import com.navio.usermanagementservice.model.UserRoleAssignment;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.AuditLogRepository;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserRoleRepository;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID MODERATOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserBanRepository userBanRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, userRoleRepository, userBanRepository,
                auditLogRepository, keycloakAdminClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void statisticsExcludeDeletedProfilesAndReportTheirThirtyDayBoundary() {
        Instant since = Instant.parse("2026-08-25T10:00:00Z");
        when(userRepository.countByDeletedAtIsNullAndStatusNot(UserStatus.DELETED)).thenReturn(42L);
        when(userRepository.countByDeletedAtIsNullAndStatus(UserStatus.ACTIVE)).thenReturn(39L);
        when(userRepository.countByDeletedAtIsNullAndStatus(UserStatus.SUSPENDED)).thenReturn(3L);
        when(userRepository.countByDeletedAtIsNullAndStatusNotAndCreatedAtGreaterThanEqual(UserStatus.DELETED, since))
                .thenReturn(7L);

        AdminStatisticsResponse statistics = service.statistics();

        assertThat(statistics).isEqualTo(new AdminStatisticsResponse(42, 39, 3, 7, since, NOW));
    }

    @Test
    void unfilteredListUsesAQueryWithoutNullParameters() {
        when(userRepository.findAllBy(any())).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.search(null, null, PageRequest.of(0, 20))).isEmpty();

        verify(userRepository).findAllBy(any());
        verify(userRepository, never()).searchByTerm(any(), any());
    }

    @Test
    void detailReadsLiveRolesFromKeycloakAndDescribesTheActiveSuspension() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "Target", UserStatus.SUSPENDED)));
        when(keycloakAdminClient.realmRolesOf("kc-target")).thenReturn(List.of(NavioRole.USER, NavioRole.ADMIN));
        when(userBanRepository.findActiveBans(USER_ID, NOW)).thenReturn(List.of(UserBan.builder()
                .userId(USER_ID).reason("Spam").bannedByUserId(MODERATOR_ID)
                .startsAt(Instant.parse("2026-09-20T00:00:00Z")).build()));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(MODERATOR_ID, "Mod", UserStatus.ACTIVE)));

        AdminUserDetailResponse detail = service.detail(USER_ID);

        // An admin granted in the Keycloak console is not in the local snapshot,
        // so only the live read shows ADMIN here.
        assertThat(detail.roles()).containsExactly(NavioRole.USER, NavioRole.ADMIN);
        assertThat(detail.rolesVerified()).isTrue();
        assertThat(detail.activeSuspension().reason()).isEqualTo("Spam");
        assertThat(detail.activeSuspension().bannedByDisplayName()).isEqualTo("Mod");
        verify(userRoleRepository, never()).findByUserId(any());
    }

    @Test
    void detailFallsBackToTheSnapshotAndSaysSoWhenKeycloakIsDown() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "Target", UserStatus.ACTIVE)));
        when(keycloakAdminClient.realmRolesOf("kc-target")).thenThrow(new KeycloakAdminException("down"));
        when(userRoleRepository.findByUserId(USER_ID)).thenReturn(List.of(UserRoleAssignment.builder()
                .userId(USER_ID).role(NavioRole.MODERATOR).build()));
        when(userBanRepository.findActiveBans(USER_ID, NOW)).thenReturn(List.of());

        AdminUserDetailResponse detail = service.detail(USER_ID);

        assertThat(detail.roles()).containsExactly(NavioRole.MODERATOR);
        assertThat(detail.rolesVerified()).isFalse();
        assertThat(detail.activeSuspension()).isNull();
    }

    @Test
    void detailOfAnUnknownUserIsNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(USER_ID)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void moderationHistoryShowsOnlyModerationActionsWithReasonAndActorName() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);
        AuditLog suspension = AuditLog.builder().id(UUID.randomUUID()).actorUserId(MODERATOR_ID)
                .action(AuditAction.USER_SUSPENDED).resourceType(AuditAction.RESOURCE_USER).resourceId(USER_ID)
                .before(Map.of("status", "active")).after(Map.of("status", "suspended"))
                .metadata(Map.of("reason", "Spam", "banId", "x")).createdAt(NOW).build();
        when(auditLogRepository.findByResourceTypeAndResourceIdAndActionInOrderByCreatedAtDesc(
                eq(AuditAction.RESOURCE_USER), eq(USER_ID), eq(AdminUserService.MODERATION_ACTIONS), any()))
                .thenReturn(new PageImpl<>(List.of(suspension)));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(MODERATOR_ID, "Mod", UserStatus.ACTIVE)));

        ModerationEventResponse event = service.moderationEvents(USER_ID, PageRequest.of(0, 20)).getContent().getFirst();

        assertThat(event.action()).isEqualTo(AuditAction.USER_SUSPENDED);
        assertThat(event.reason()).isEqualTo("Spam");
        assertThat(event.actorDisplayName()).isEqualTo("Mod");
        assertThat(event.role()).isNull();
        // Profile edits carry personal data and are not moderation decisions.
        assertThat(AdminUserService.MODERATION_ACTIONS).doesNotContain(AuditAction.USER_PROFILE_UPDATED);
    }

    @Test
    void moderationHistoryOfAnUnknownUserIsNotFound() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.moderationEvents(USER_ID, PageRequest.of(0, 20)))
                .isInstanceOf(UserNotFoundException.class);
    }

    private User user(UUID id, String name, UserStatus status) {
        return User.builder().id(id).authSubject(id.equals(USER_ID) ? "kc-target" : "kc-other")
                .email(name.toLowerCase() + "@example.com").displayName(name).status(status)
                .preferences(new HashMap<>()).createdAt(NOW).updatedAt(NOW).build();
    }
}
