package com.navio.usermanagementservice.dto;

import java.time.Instant;
import java.util.List;

/**
 * How the caller signs in, so the Security page can offer the right actions.
 *
 * <p>Returned only to the account owner and contains no credential material:
 * whether a password exists, never anything about it.
 *
 * @param passwordSet     the account has a Navio (Keycloak) password
 * @param linkedProviders external sign-in providers linked to the account, such as {@code google}
 * @param emailVerified   Keycloak's verification flag for the current email
 * @param authenticatedAt when the current session last authenticated, from the token
 * @param available       false when Keycloak could not be asked; the other fields are then unknown
 */
public record SecurityOverviewResponse(
        boolean passwordSet,
        List<String> linkedProviders,
        boolean emailVerified,
        Instant authenticatedAt,
        boolean available
) {
}
