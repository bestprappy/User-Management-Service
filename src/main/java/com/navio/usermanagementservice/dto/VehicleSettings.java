package com.navio.usermanagementservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Optional fields are merged on update; unknown charging limits stay null. */
public record VehicleSettings(
        @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal maxAcKw,
        @DecimalMin("0") @DecimalMax("2000") @Digits(integer = 4, fraction = 2) BigDecimal maxDcKw,
        @Min(0) @Max(100) Integer startingBatteryPct,
        @Size(max = 2048)
        @Pattern(regexp = "^(|/images/vehicles/[a-z0-9-]+\\.(?:webp|png)|https://[^\\s]+)$",
                message = "imageUrl must be an HTTPS URL or a vehicle image path")
        String imageUrl
) {
}
