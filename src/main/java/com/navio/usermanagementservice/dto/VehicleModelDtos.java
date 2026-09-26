package com.navio.usermanagementservice.dto;

import com.navio.usermanagementservice.model.VehicleModel.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class VehicleModelDtos {
    private VehicleModelDtos() {}
    public record Fields(
            @NotBlank @Size(max = 100) String make,
            @NotBlank @Size(max = 100) String model,
            @NotNull @Size(max = 100) String trim,
            @Min(1900) @Max(2200) Short year,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String market,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2) BigDecimal batteryCapacityKwh,
            @NotNull @Pattern(regexp = "MANUFACTURER_DECLARED|USABLE|GROSS|UNKNOWN") String batteryCapacityBasis,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2) BigDecimal rangeKm,
            @Pattern(regexp = "NEDC|WLTP|EPA|CLTC") String rangeStandard,
            @NotNull @Size(max = 7) List<@NotNull @Pattern(regexp = "CCS1|CCS2|TYPE2|J1772|CHADEMO|NACS|GB_T") String> connectorTypes,
            @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal maxAcKw,
            @DecimalMin("0") @DecimalMax("2000") @Digits(integer = 4, fraction = 2) BigDecimal maxDcKw,
            @Size(max = 2048) String imageUrl,
            @Size(max = 2048) String sourceUrl,
            @PastOrPresent LocalDate verifiedAt) {}
    public record WriteRequest(@PositiveOrZero Long expectedVersion, @NotNull @Valid Fields specification) {}
    public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record AdminResponse(String id, Status status, long version, Fields specification, Instant createdAt, Instant updatedAt) {}
}
