package com.navio.usermanagementservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved personal anchor as returned to its owner.
 *
 * <p>There is no public counterpart to this record on purpose: a saved place can
 * be a home address, so it is never exposed on a profile, a shared trip, or a
 * published template.
 */
public record SavedPlaceResponse(
        UUID id,
        String label,
        String kind,
        String name,
        String address,
        double lat,
        double lng,
        String providerPlaceId,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) {
}
