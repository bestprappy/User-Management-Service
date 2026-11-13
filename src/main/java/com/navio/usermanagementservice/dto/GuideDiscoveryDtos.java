package com.navio.usermanagementservice.dto;

import com.navio.usermanagementservice.dto.GuideDtos.Category;
import com.navio.usermanagementservice.dto.UserGuideDtos.Author;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GuideDiscoveryDtos {
    private GuideDiscoveryDtos() {}
    public record Entry(UUID id, String name, String country, String countryCode,
            String destinationName, String destinationProvider, String destinationPlaceId,
            String description, String coverPlaceId, int placeCount, List<Category> categories,
            Author author, Instant publishedAt, Instant updatedAt, String source, long viewCount,
            String discoveryLabel, List<String> collections, Double ratingAverage, long ratingCount) {}
    public record Response(List<Entry> content, List<Entry> matching, List<Entry> relevant,
            long matchingTotal, long relevantTotal, long totalElements, int number, boolean last,
            List<String> suggestions, String notice) {}
}
