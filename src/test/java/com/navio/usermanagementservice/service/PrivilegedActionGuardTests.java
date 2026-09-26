package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.model.UserBan;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivilegedActionGuardTests {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final String ACTOR_SUBJECT = "kc-subject-admin";

    @Mock private UserRepository userRepository;
    @Mock private UserBanRepository userBanRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;
    @InjectMocks private PrivilegedActionGuard guard;

    private final AuthenticatedUser actor = new AuthenticatedUser(ACTOR_ID, ACTOR_SUBJECT,
            "admin@example.com", "Admin", Set.of(NavioRole.ADMIN));

    @Test
    void locksBothProfilesBeforeReadingTheActorsStanding() {
        when(userBanRepository.findActiveBans(eq(ACTOR_ID), any())).thenReturn(List.of());
        when(keycloakAdminClient.realmRolesOf(ACTOR_SUBJECT)).thenReturn(List.of(NavioRole.ADMIN));

        guard.confirmAdministratorMayAct(actor, TARGET_ID);

        // The checks are only meaningful after the lock: read before it, they
        // could miss a crossing action that commits while this one waits.
        InOrder order = inOrder(userRepository, userBanRepository, keycloakAdminClient);
        order.verify(userRepository).lockForModeration(List.of(ACTOR_ID, TARGET_ID));
        order.verify(userBanRepository).findActiveBans(eq(ACTOR_ID), any());
        order.verify(keycloakAdminClient).realmRolesOf(ACTOR_SUBJECT);
    }

    @Test
    void refusesAnAdministratorWhoWasSuspendedWhileTheActionWaited() {
        when(userBanRepository.findActiveBans(eq(ACTOR_ID), any())).thenReturn(List.of(UserBan.builder()
                .userId(ACTOR_ID).reason("Crossing suspension").bannedByUserId(TARGET_ID)
                .startsAt(Instant.parse("2026-09-24T00:00:00Z")).build()));

        assertThatThrownBy(() -> guard.confirmAdministratorMayAct(actor, TARGET_ID))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void refusesAnAdministratorWhoseRoleWasRevokedEvenThoughTheTokenStillSaysAdmin() {
        when(userBanRepository.findActiveBans(eq(ACTOR_ID), any())).thenReturn(List.of());
        when(keycloakAdminClient.realmRolesOf(ACTOR_SUBJECT)).thenReturn(List.of(NavioRole.USER));

        assertThatThrownBy(() -> guard.confirmAdministratorMayAct(actor, TARGET_ID))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("administrator role was removed");
    }

    @Test
    void allowsAnAdministratorInGoodStanding() {
        when(userBanRepository.findActiveBans(eq(ACTOR_ID), any())).thenReturn(List.of());
        when(keycloakAdminClient.realmRolesOf(ACTOR_SUBJECT)).thenReturn(List.of(NavioRole.USER, NavioRole.ADMIN));

        assertThatCode(() -> guard.confirmAdministratorMayAct(actor, TARGET_ID)).doesNotThrowAnyException();
    }
}
