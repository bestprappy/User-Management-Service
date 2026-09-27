package com.navio.usermanagementservice.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The four global roles Navio recognises.
 *
 * <p>Keycloak is authoritative for these; {@code iam.user_roles} only mirrors
 * them for display and audit. Resource-scoped roles (trip editor, group
 * moderator) are deliberately absent — they belong to the owning domain service
 * and must never be promoted to a global authority.
 */
public enum NavioRole {

    USER,
    MODERATOR,
    ADMIN,
    OWNER;

    public static final String ROLE_PREFIX = "ROLE_";

    /** Spring Security authority name, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return ROLE_PREFIX + name();
    }

    /**
     * Resolves a Keycloak role name against the allowlist.
     *
     * <p>An allowlist is used rather than a denylist so that Keycloak's built-in
     * roles ({@code offline_access}, {@code uma_authorization},
     * {@code default-roles-*}) and any future realm role added by an operator
     * cannot become a Navio authority by accident.
     *
     * @return the matching role, or empty when the name is not a Navio role.
     */
    public static Optional<NavioRole> fromClaim(String claimValue) {
        if (claimValue == null || claimValue.isBlank()) {
            return Optional.empty();
        }
        String normalized = claimValue.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.name().equals(normalized))
                .findFirst();
    }
}
