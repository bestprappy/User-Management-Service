package com.navio.usermanagementservice.dto;

import com.navio.usermanagementservice.model.Guide.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response shapes for destination guides.
 *
 * <p>The content mirrors the guide editor's document: categories, list
 * headers, places and the block text as markdown. Ids are the values the
 * editor writes into the text, so they are restricted to the characters the
 * text's block markers accept.
 */
public final class GuideDtos {
    private GuideDtos() {}

    /** Ids written into the guide text: list ids, category ids, provider place ids. */
    public static final String BLOCK_ID = "[A-Za-z0-9_-]{1,255}";
    public static final String ICON_IDS = "landmark|utensils|bed|coffee|wine|shopping|trees|waves|mountain|camera|ticket|music|train|charging|baby|heart";
    public static final String PROVIDERS = "GOOGLE|MAPBOX";

    public record Category(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
            @NotBlank @Size(max = 40) String name,
            @NotNull @Pattern(regexp = ICON_IDS) String iconId,
            @NotNull @Pattern(regexp = PaletteColorDtos.ID) String colorId) {}

    public record ListHeader(
            @NotNull @Pattern(regexp = BLOCK_ID) String id,
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String categoryId) {}

    public record Place(
            @NotBlank @Size(max = 300) String id,
            @NotNull @Pattern(regexp = PROVIDERS) String provider,
            @NotNull @Pattern(regexp = BLOCK_ID) String providerPlaceId,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Size(max = 500) String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @Size(max = 100) String primaryType,
            @Size(max = 100) String category,
            @NotNull @Size(max = 6) List<@NotBlank @Size(max = 24) String> tags) {}

    public record Content(
            @NotNull @Size(max = 30) List<@NotNull @Valid Category> categories,
            @NotNull @Size(max = 200) Map<@Pattern(regexp = BLOCK_ID) String, @NotNull @Valid ListHeader> lists,
            @NotNull @Size(max = 600) Map<@Pattern(regexp = BLOCK_ID) String, @NotNull @Valid Place> places,
            @NotNull @Size(max = 200_000) String body) {}

    public record Destination(
            @NotNull @Pattern(regexp = PROVIDERS) String provider,
            @NotBlank @Size(max = 255) String providerPlaceId,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {}

    public record Fields(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Size(max = 100) String country,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @NotNull @Valid Destination destination,
            @NotNull @Size(max = 4000) String description,
            @NotBlank @Size(max = 300) String coverPlaceId,
            @NotNull @Valid Content content) {}

    public record CreateRequest(@NotNull @Valid Fields guide) {}
    public record WriteRequest(@NotNull @PositiveOrZero Long expectedVersion, @NotNull @Valid Fields guide) {}
    public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

    /** A guide on the admin dashboard: enough for its card, without the document. */
    public record Summary(
            UUID id, String name, String country, String countryCode, String coverPlaceId,
            String destinationProvider, String destinationPlaceId,
            Status status, long version, int placeCount, List<Category> categories,
            Instant updatedAt, String updatedByName, Instant publishedAt) {}

    public record AdminResponse(
            UUID id, Status status, long version, Fields guide,
            Instant createdAt, Instant updatedAt, Instant publishedAt,
            String createdByName, String updatedByName) {}

    /** A published guide as travelers read it. */
    public record PublicResponse(UUID id, Fields guide, Instant publishedAt, Instant updatedAt) {}
}
