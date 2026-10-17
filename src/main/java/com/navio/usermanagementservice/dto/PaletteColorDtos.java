package com.navio.usermanagementservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request and response shapes for the admin-managed color palette. */
public final class PaletteColorDtos {
    private PaletteColorDtos() {}

    /** A palette color id. Safe to embed in a CSS custom property name. */
    public static final String ID = "[a-z][a-z0-9-]{0,39}";

    public record CreateRequest(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "Use a hex color such as #E8590C") String hex) {}

    /**
     * Every color, retired ones included: pickers offer only active colors, but
     * a trip or guide saved with a retired color must still render it.
     */
    public record PublicResponse(String id, String name, String hex, boolean retired) {}

    public record AdminResponse(
            String id, String name, String hex, int sortOrder, boolean retired,
            Instant retiredAt, Instant createdAt, Instant updatedAt) {}
}
