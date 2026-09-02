package com.navio.usermanagementservice.dto;

import com.navio.usermanagementservice.security.NavioRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated user's own profile.
 *
 * <p>Returned only to the account owner, so it may carry the email address and
 * account status. Compare {@link PublicUserProfileResponse}, which is what other
 * users are allowed to see.
 */
public record UserProfileResponse(
        UUID id,
        String displayName,
        String email,
        UUID avatarMediaId,
        String status,
        String locale,
        String countryCode,
        List<NavioRole> roles,
        UserPreferences preferences,
        Instant createdAt,
        Instant updatedAt
) {
}
