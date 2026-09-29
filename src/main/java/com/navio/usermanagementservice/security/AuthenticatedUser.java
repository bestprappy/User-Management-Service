package com.navio.usermanagementservice.security;

import java.util.Set;
import java.util.UUID;

/**
 * The caller's resolved identity for the current request.
 *
 * <p>Every field originates from a cryptographically validated Keycloak token or
 * from the Navio profile that token maps to. Nothing here is client-supplied,
 * which is the whole point: controllers must take the acting user from this
 * record and never from a request header, path variable, or body field.
 *
 * @param id         internal {@code iam.users.id}, used for all ownership checks.
 * @param authSubject Keycloak {@code sub}; stable for the life of the account.
 * @param email      verified email from the token.
 * @param displayName current profile display name.
 * @param roles      global roles carried by the validated token.
 */
public record AuthenticatedUser(
        UUID id,
        String authSubject,
        String email,
        String displayName,
        Set<NavioRole> roles
) {

    public AuthenticatedUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(NavioRole role) {
        return roles.contains(role);
    }

    /** Moderators and admins may act on other users' records. */
    public boolean canModerate() {
        return hasRole(NavioRole.MODERATOR) || hasRole(NavioRole.ADMIN) || hasRole(NavioRole.OWNER);
    }

    public boolean isAdmin() {
        return hasRole(NavioRole.ADMIN) || hasRole(NavioRole.OWNER);
    }
}
