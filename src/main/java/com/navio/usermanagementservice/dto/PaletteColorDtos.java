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
    /** Label color drawn on a palette color: best contrast, or always white or black. */
    public static final String TEXT_COLOR = "auto|white|black";
    private static final String TEXT_COLOR_MESSAGE = "Use auto, white or black";

    /** Text colors are optional and default to {@code auto}. */
    public record CreateRequest(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "Use a hex color such as #E8590C") String hex,
            @Pattern(regexp = TEXT_COLOR, message = TEXT_COLOR_MESSAGE) String textOnLight,
            @Pattern(regexp = TEXT_COLOR, message = TEXT_COLOR_MESSAGE) String textOnDark) {
        public CreateRequest(String name, String hex) { this(name, hex, null, null); }
    }

    /** The only editable part of a color; its name and hex stay fixed. */
    public record UpdateTextColorRequest(
            @NotNull @Pattern(regexp = TEXT_COLOR, message = TEXT_COLOR_MESSAGE) String textOnLight,
            @NotNull @Pattern(regexp = TEXT_COLOR, message = TEXT_COLOR_MESSAGE) String textOnDark) {}

    /**
     * Every color, retired ones included: pickers offer only active colors, but
     * a trip or guide saved with a retired color must still render it. Deleted
     * ids are listed too, flagged and retired, carrying the default color's hex
     * so readers draw saved references in the default color.
     */
    public record PublicResponse(String id, String name, String hex, boolean retired, boolean deleted,
                                 String textOnLight, String textOnDark) {}

    public record AdminResponse(
            String id, String name, String hex, int sortOrder, boolean retired,
            Instant retiredAt, Instant createdAt, Instant updatedAt, String textOnLight, String textOnDark) {}
}
