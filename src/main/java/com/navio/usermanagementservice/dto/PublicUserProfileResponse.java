package com.navio.usermanagementservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * What one user may see about another.
 *
 * <p>Email, locale, country, preferences, and role membership are all absent by
 * design. {@code GET /v1/users/{userId}} is reachable by any authenticated
 * caller, so anything included here is effectively public to the whole user
 * base; an email address in this payload would turn the endpoint into a
 * harvesting API.
 */
public record PublicUserProfileResponse(
        UUID id,
        String displayName,
        UUID avatarMediaId,
        Instant memberSince
) {
}
