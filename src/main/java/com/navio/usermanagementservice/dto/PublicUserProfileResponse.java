package com.navio.usermanagementservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one user may see about another.
 *
 * <p>Email, locale, country, notification settings and role membership are all
 * absent by design. Anything included here is effectively public to the whole
 * user base; an email address in this payload would turn the endpoint into a
 * harvesting API.
 *
 * <p>{@code accountId} is the Keycloak subject, the id Community and Trip
 * Planning already expose as a post or comment author, so the profile page can
 * ask those services for this person's public content. Travel interests and
 * pace are empty when the owner chose to hide them.
 */
public record PublicUserProfileResponse(
        UUID id,
        String displayName,
        UUID avatarMediaId,
        Instant memberSince,
        String username,
        UUID accountId,
        String bio,
        UUID bannerMediaId,
        List<String> travelInterests,
        String travelPace
) {
    /** The original four-field shape, kept for callers that only need the byline. */
    public PublicUserProfileResponse(UUID id, String displayName, UUID avatarMediaId, Instant memberSince) {
        this(id, displayName, avatarMediaId, memberSince, null, null, null, null, List.of(), null);
    }
}
