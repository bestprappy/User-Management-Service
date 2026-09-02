package com.navio.usermanagementservice.integration.keycloak;

import com.navio.usermanagementservice.integration.keycloak.KeycloakRepresentations.KeycloakUser;
import com.navio.usermanagementservice.security.NavioRole;

import java.util.List;
import java.util.Optional;

/**
 * Privileged operations against the Keycloak realm.
 *
 * <p>An interface rather than a concrete class so the moderation and role
 * services depend on the capability, not on the HTTP transport — and so tests
 * can substitute a fake without standing up a Keycloak instance.
 */
public interface KeycloakAdminClient {

    Optional<KeycloakUser> findUserBySubject(String authSubject);

    /**
     * Enables or disables the account.
     *
     * <p>Disabling stops <em>new</em> tokens from being issued. It does not
     * invalidate tokens already in the wild — see {@link #logoutUser(String)}.
     */
    void setUserEnabled(String authSubject, boolean enabled);

    /**
     * Terminates every active session and invalidates the user's refresh tokens.
     *
     * <p>Required alongside {@link #setUserEnabled} when suspending. Without it a
     * suspended user keeps working until their current access token expires, and
     * can keep minting new access tokens from an unexpired refresh token.
     */
    void logoutUser(String authSubject);

    List<NavioRole> realmRolesOf(String authSubject);

    void grantRealmRole(String authSubject, NavioRole role);

    void revokeRealmRole(String authSubject, NavioRole role);
}
