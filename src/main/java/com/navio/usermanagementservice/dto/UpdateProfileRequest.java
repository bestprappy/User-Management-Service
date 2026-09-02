package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Editable fields on the caller's own profile.
 *
 * <p>Only these four fields exist on the request type. {@code email},
 * {@code status}, {@code roles}, {@code authSubject}, and {@code id} are absent
 * so that binding cannot reach them: a mass-assignment attempt like
 * {@code {"status":"active","roles":["ADMIN"]}} has nowhere to land. Email and
 * roles are Keycloak's to change, and status is moderation's.
 *
 * <p>Null means "leave unchanged".
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateProfileRequest(

        @Size(min = 1, max = 120, message = "displayName must be 1-120 characters")
        @Pattern(regexp = "^(?!\\s*$).+", message = "displayName must not be blank")
        String displayName,

        UUID avatarMediaId,

        @Pattern(regexp = "^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$",
                message = "locale must be a language tag such as 'en' or 'th'")
        @Size(max = 20)
        String locale,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be a 2-letter ISO code")
        String countryCode
) {
}
