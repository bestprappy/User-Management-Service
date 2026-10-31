package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.exception.UserManagementExceptions.ReauthenticationRequiredException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAccountReader;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakRepresentations.KeycloakUser;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountSecurityServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final String SUBJECT = "00000000-0000-4000-8000-0000000000aa";

    @Mock KeycloakAdminClient keycloak;
    @Mock KeycloakAccountReader reader;
    @Mock AuditService audit;
    private AccountSecurityService service;
    private final AuthenticatedUser caller = new AuthenticatedUser(UUID.randomUUID(), SUBJECT, "me@example.com", "Me", Set.of());

    @BeforeEach
    void setUp() {
        service = new AccountSecurityService(keycloak, reader, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anExternalSignInAccountWithoutAPasswordIsReportedAsSuch() {
        when(keycloak.findUserBySubject(SUBJECT)).thenReturn(Optional.of(new KeycloakUser(SUBJECT, "me", "me@example.com", true, true, null, null)));
        when(reader.credentialTypes(SUBJECT)).thenReturn(List.of());
        when(reader.linkedIdentityProviders(SUBJECT)).thenReturn(List.of("google"));
        var overview = service.overview(caller, NOW);
        assertThat(overview.passwordSet()).isFalse();
        assertThat(overview.linkedProviders()).containsExactly("google");
        assertThat(overview.emailVerified()).isTrue();
        assertThat(overview.available()).isTrue();
    }

    @Test
    void aPasswordCredentialIsDetectedByTypeOnly() {
        when(keycloak.findUserBySubject(SUBJECT)).thenReturn(Optional.empty());
        when(reader.credentialTypes(SUBJECT)).thenReturn(List.of("password", "otp"));
        when(reader.linkedIdentityProviders(SUBJECT)).thenReturn(List.of());
        assertThat(service.overview(caller, NOW).passwordSet()).isTrue();
    }

    @Test
    void anUnreachableKeycloakDegradesTheOverviewInsteadOfFailing() {
        when(keycloak.findUserBySubject(SUBJECT)).thenThrow(new KeycloakAdminException("down", null));
        var overview = service.overview(caller, NOW);
        assertThat(overview.available()).isFalse();
        assertThat(overview.passwordSet()).isFalse();
    }

    @Test
    void signingOutEverywhereNeedsASignInFromTheLastTenMinutes() {
        assertThatThrownBy(() -> service.signOutEverywhere(caller, NOW.minusSeconds(11 * 60)))
                .isInstanceOf(ReauthenticationRequiredException.class);
        assertThatThrownBy(() -> service.signOutEverywhere(caller, null))
                .isInstanceOf(ReauthenticationRequiredException.class);
        verify(keycloak, never()).logoutUser(anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void aRecentSignInEndsEverySessionAndIsAudited() {
        service.signOutEverywhere(caller, NOW.minusSeconds(60));
        verify(keycloak).logoutUser(SUBJECT);
        verify(audit).record(any(), any(String.class), any(String.class), any(UUID.class), any());
    }
}
