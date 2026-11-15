package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.TreeMap;

/**
 * The user's stored preferences.
 *
 * <p>Modelled as a fixed record rather than a free-form map even though the
 * column is JSONB. A map would let any caller write unbounded arbitrary keys
 * into the database — an easy denial-of-service through storage growth, and a
 * place to stash content that later gets rendered by some other client.
 *
 * <p>Unknown JSON properties are rejected (not ignored) so a typo in a client
 * payload surfaces as a 400 instead of silently discarding the setting.
 *
 * @param language          BCP-47-ish language tag, constrained to letters and hyphen.
 * @param distanceUnit      {@code km} or {@code mi}.
 * @param notificationEmail whether transactional email is enabled.
 * @param notificationPush  whether push notification is enabled.
 * @param tipsHidden        the person chose "Don't show me again": first-time tips no longer open by themselves.
 * @param seenTips          first-time tip tours already shown to this account, by tour id, with the tour
 *                          version shown. Bounded in size, key shape and value range; ids are the client's.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UserPreferences(

        @Pattern(regexp = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$",
                message = "language must be a language tag such as 'en' or 'th'")
        String language,

        @Pattern(regexp = "^(km|mi)$", message = "distanceUnit must be 'km' or 'mi'")
        String distanceUnit,

        Boolean notificationEmail,

        Boolean notificationPush,

        Boolean tipsHidden,

        @Size(max = MAX_SEEN_TIPS, message = "seenTips can hold at most 40 tours")
        Map<
                @NotNull
                @Pattern(regexp = TIP_ID_PATTERN, message = "seenTips keys must be tour ids such as 'planner-auto-plan'")
                String,
                @NotNull(message = "seenTips versions are required")
                @Min(value = 1, message = "seenTips versions start at 1")
                @Max(value = 1000, message = "seenTips versions must be 1000 or lower")
                Integer> seenTips
) {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final String DEFAULT_DISTANCE_UNIT = "km";
    /** Navio ships ten tours; the cap leaves room to grow without letting the column grow unbounded. */
    public static final int MAX_SEEN_TIPS = 40;
    public static final String TIP_ID_PATTERN = "^[a-z][a-z0-9-]{0,39}$";

    /** Preferences without the tip fields, as older callers and tests build them. */
    public UserPreferences(String language, String distanceUnit, Boolean notificationEmail, Boolean notificationPush) {
        this(language, distanceUnit, notificationEmail, notificationPush, null, null);
    }

    /** Preferences applied to a freshly provisioned profile. */
    public static UserPreferences defaults() {
        return new UserPreferences(DEFAULT_LANGUAGE, DEFAULT_DISTANCE_UNIT, true, false, false, Map.of());
    }

    /**
     * Returns a copy where every null field falls back to the current value, so a
     * PATCH can send only the keys it means to change.
     *
     * <p>{@code seenTips} is merged per tour and keeps the higher version, so two
     * tabs each recording a different tour never erase each other, and a tour
     * once seen is never "unseen" by a stale write. The merged map is capped at
     * {@link #MAX_SEEN_TIPS}; tours that would overflow it are dropped.
     */
    public UserPreferences mergedOnto(UserPreferences current) {
        UserPreferences base = current == null ? defaults() : current;
        return new UserPreferences(
                language != null ? language : base.language(),
                distanceUnit != null ? distanceUnit : base.distanceUnit(),
                notificationEmail != null ? notificationEmail : base.notificationEmail(),
                notificationPush != null ? notificationPush : base.notificationPush(),
                tipsHidden != null ? tipsHidden : Boolean.TRUE.equals(base.tipsHidden()),
                mergeSeenTips(base.seenTips(), seenTips)
        );
    }

    private static Map<String, Integer> mergeSeenTips(Map<String, Integer> current, Map<String, Integer> update) {
        Map<String, Integer> merged = new TreeMap<>();
        if (current != null) {
            current.forEach((id, version) -> {
                if (id != null && version != null) merged.put(id, version);
            });
        }
        if (update != null) {
            update.forEach((id, version) -> {
                if (id == null || version == null) return;
                if (merged.containsKey(id) || merged.size() < MAX_SEEN_TIPS) {
                    merged.merge(id, version, Math::max);
                }
            });
        }
        return Map.copyOf(merged);
    }
}
