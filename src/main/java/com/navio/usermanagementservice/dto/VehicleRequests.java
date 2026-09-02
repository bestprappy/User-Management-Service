package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request payloads for the saved-vehicle garage.
 *
 * <p>Numeric bounds are not cosmetic: these values feed EV range optimisation in
 * the trip service, so an unbounded or negative capacity would propagate a
 * nonsensical route calculation across service boundaries. Bounds also keep the
 * values inside the {@code NUMERIC(8,2)} columns, so a bad input fails
 * validation with a clear 400 rather than a database overflow error.
 *
 * <p>The list size is bounded here; the character set of each connector value is
 * enforced in {@code UserVehicleService} during normalisation, which keeps the
 * check in one place alongside the trimming and de-duplication it belongs with.
 */
public final class VehicleRequests {

    private VehicleRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateVehicleRequest(

            @Size(max = 100, message = "nickname must be at most 100 characters")
            String nickname,

            @NotBlank(message = "make is required")
            @Size(max = 100)
            String make,

            @NotBlank(message = "model is required")
            @Size(max = 100)
            String model,

            @Min(value = 1900, message = "year must be 1900 or later")
            @Max(value = 2200, message = "year must be 2200 or earlier")
            Short year,

            @NotNull(message = "batteryCapacityKwh is required")
            @DecimalMin(value = "0.1", message = "batteryCapacityKwh must be greater than 0")
            @DecimalMax(value = "999999.99", message = "batteryCapacityKwh is out of range")
            BigDecimal batteryCapacityKwh,

            @NotNull(message = "rangeKm is required")
            @DecimalMin(value = "0.1", message = "rangeKm must be greater than 0")
            @DecimalMax(value = "999999.99", message = "rangeKm is out of range")
            BigDecimal rangeKm,

            @DecimalMin(value = "0.0", inclusive = false, message = "consumptionKwhPer100km must be greater than 0")
            @DecimalMax(value = "99999.999", message = "consumptionKwhPer100km is out of range")
            BigDecimal consumptionKwhPer100km,

            @NotNull(message = "connectorTypes is required")
            @Size(max = 20, message = "at most 20 connector types may be listed")
            List<String> connectorTypes,

            Boolean isDefault
    ) {
    }

    /** Every field is optional; null means "leave unchanged". */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateVehicleRequest(

            @Size(max = 100)
            String nickname,

            @Size(max = 100)
            String make,

            @Size(max = 100)
            String model,

            @Min(1900)
            @Max(2200)
            Short year,

            @DecimalMin(value = "0.1", message = "batteryCapacityKwh must be greater than 0")
            @DecimalMax(value = "999999.99")
            BigDecimal batteryCapacityKwh,

            @DecimalMin(value = "0.1", message = "rangeKm must be greater than 0")
            @DecimalMax(value = "999999.99")
            BigDecimal rangeKm,

            @DecimalMin(value = "0.0", inclusive = false)
            @DecimalMax(value = "99999.999")
            BigDecimal consumptionKwhPer100km,

            @Size(max = 20, message = "at most 20 connector types may be listed")
            List<String> connectorTypes,

            Boolean isDefault
    ) {
    }
}
