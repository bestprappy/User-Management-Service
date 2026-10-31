package com.navio.usermanagementservice.dto;

import java.util.List;
import java.util.Set;

/** The fixed vocabulary for optional public travel preferences. */
public final class TravelProfile {

    public static final int MAX_INTERESTS = 8;

    public static final Set<String> INTERESTS = Set.of(
            "nature", "food", "road_trips", "culture", "beaches", "mountains", "cities",
            "nightlife", "history", "photography", "adventure", "wellness", "shopping",
            "family", "budget", "luxury", "ev_travel");

    public static final Set<String> PACES = Set.of("relaxed", "balanced", "packed");

    private TravelProfile() {
    }

    /** Distinct, known values in the caller's order. Unknown values are rejected by the caller first. */
    public static List<String> sanitizeInterests(List<String> requested) {
        return requested.stream().filter(INTERESTS::contains).distinct().limit(MAX_INTERESTS).toList();
    }
}
