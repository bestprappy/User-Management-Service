package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payloads for the caller's saved personal anchors.
 *
 * <p>Coordinate bounds are enforced here as well as in the database so a bad
 * input fails with a clear {@code 400} instead of a constraint violation, and so
 * a non-finite value can never reach the routing calls that consume it.
 */
public final class SavedPlaceRequests {

    private SavedPlaceRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateSavedPlaceRequest(

            @NotBlank(message = "label is required")
            @Size(max = 80, message = "label must be at most 80 characters")
            String label,

            /** HOME, WORK or CUSTOM; defaults to CUSTOM when omitted. */
            @Size(max = 20)
            String kind,

            @NotBlank(message = "name is required")
            @Size(max = 255)
            String name,

            @Size(max = 512)
            String address,

            @NotNull(message = "lat is required")
            @DecimalMin(value = "-90", message = "lat must be between -90 and 90")
            @DecimalMax(value = "90", message = "lat must be between -90 and 90")
            Double lat,

            @NotNull(message = "lng is required")
            @DecimalMin(value = "-180", message = "lng must be between -180 and 180")
            @DecimalMax(value = "180", message = "lng must be between -180 and 180")
            Double lng,

            @Size(max = 512)
            String providerPlaceId,

            Boolean isDefault
    ) {
    }

    /** Every field is optional; null means "leave unchanged". */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateSavedPlaceRequest(

            @Size(max = 80)
            String label,

            @Size(max = 20)
            String kind,

            @Size(max = 255)
            String name,

            @Size(max = 512)
            String address,

            @DecimalMin(value = "-90", message = "lat must be between -90 and 90")
            @DecimalMax(value = "90", message = "lat must be between -90 and 90")
            Double lat,

            @DecimalMin(value = "-180", message = "lng must be between -180 and 180")
            @DecimalMax(value = "180", message = "lng must be between -180 and 180")
            Double lng,

            @Size(max = 512)
            String providerPlaceId,

            Boolean isDefault
    ) {
    }
}
