package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.SecurityOverviewResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ReauthenticationRequiredException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAccountReader;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminException;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The owner-only Security page.
 *
 * <p>Keycloak stays authoritative for credentials: passwords and email are
 * changed through Keycloak's own re-authenticating flows, never through this
 * service. Here we only report how the account signs in, and offer one
 * sensitive action (signing out every session) that requires a recent sign-in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountSecurityService {

    /** How recent a sign-in must be before a sensitive action is allowed. */
    public static final Duration RECENT_AUTHENTICATION = Duration.ofMinutes(10);

    private final KeycloakAdminClient keycloak;
    private final KeycloakAccountReader accountReader;
    private final AuditService audit;
    private final Clock clock;

    public SecurityOverviewResponse overview(AuthenticatedUser caller, Instant authenticatedAt) {
        try {
            var account = keycloak.findUserBySubject(caller.authSubject());
            List<String> credentials = accountReader.credentialTypes(caller.authSubject());
            List<String> providers = accountReader.linkedIdentityProviders(caller.authSubject());
            boolean verified = account.map(user -> Boolean.TRUE.equals(user.emailVerified())).orElse(false);
            return new SecurityOverviewResponse(credentials.contains("password"), providers, verified,
                    authenticatedAt, true);
        } catch (KeycloakAdminException exception) {
            // The page still renders and links to Keycloak's account console.
            log.warn("Security overview unavailable for user {}: Keycloak could not be reached", caller.id());
            return new SecurityOverviewResponse(false, List.of(), false, authenticatedAt, false);
        }
    }

    /**
     * Ends every session of the caller, including this one.
     *
     * @throws ReauthenticationRequiredException when the current token's sign-in is older than
     *                                           {@link #RECENT_AUTHENTICATION} or unknown
     */
    public void signOutEverywhere(AuthenticatedUser caller, Instant authenticatedAt) {
        if (authenticatedAt == null
                || authenticatedAt.plus(RECENT_AUTHENTICATION).isBefore(clock.instant())) {
            throw new ReauthenticationRequiredException();
        }
        keycloak.logoutUser(caller.authSubject());
        audit.record(caller.id(), AuditAction.USER_SESSIONS_REVOKED, AuditAction.RESOURCE_USER, caller.id(),
                Map.of("scope", "all"));
    }
}
