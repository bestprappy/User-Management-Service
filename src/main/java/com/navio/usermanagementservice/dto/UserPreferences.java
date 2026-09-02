package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;

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
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UserPreferences(

        @Pattern(regexp = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$",
                message = "language must be a language tag such as 'en' or 'th'")
        String language,

        @Pattern(regexp = "^(km|mi)$", message = "distanceUnit must be 'km' or 'mi'")
        String distanceUnit,

        Boolean notificationEmail,

        Boolean notificationPush
) {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final String DEFAULT_DISTANCE_UNIT = "km";

    /** Preferences applied to a freshly provisioned profile. */
    public static UserPreferences defaults() {
        return new UserPreferences(DEFAULT_LANGUAGE, DEFAULT_DISTANCE_UNIT, true, false);
    }

    /**
     * Returns a copy where every null field falls back to the current value, so a
     * PATCH can send only the keys it means to change.
     */
    public UserPreferences mergedOnto(UserPreferences current) {
        UserPreferences base = current == null ? defaults() : current;
        return new UserPreferences(
                language != null ? language : base.language(),
                distanceUnit != null ? distanceUnit : base.distanceUnit(),
                notificationEmail != null ? notificationEmail : base.notificationEmail(),
                notificationPush != null ? notificationPush : base.notificationPush()
        );
    }
}
