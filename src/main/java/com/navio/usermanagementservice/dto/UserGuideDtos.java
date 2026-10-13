package com.navio.usermanagementservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.navio.usermanagementservice.dto.GuideDtos.*;

public final class UserGuideDtos {
    private UserGuideDtos() {}
    /** Only the existing public profile identity and avatar reference; never login or contact fields. */
    public record Author(UUID id, String displayName, UUID avatarMediaId) {}
    /** Compact discovery card; the complete document is fetched only when opened. */
    public record PublishedSummary(UUID id, String name, String country, String countryCode,
            String destinationName, String destinationProvider, String destinationPlaceId,
            String description, String coverPlaceId, int placeCount, List<Category> categories,
            Author author, Instant publishedAt, Instant updatedAt) {}
    public record PublishedResponse(UUID id, Fields guide, Author author, Instant publishedAt, Instant updatedAt) {}
}
