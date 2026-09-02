package com.navio.usermanagementservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A saved vehicle as returned to its owner.
 *
 * <p>{@code userId} is intentionally omitted: the endpoint is already scoped to
 * the authenticated caller, so echoing the owner id adds nothing and would leak
 * an internal identifier into client-side storage and logs.
 */
public record VehicleResponse(
        UUID id,
        String nickname,
        String make,
        String model,
        Short year,
        BigDecimal batteryCapacityKwh,
        BigDecimal rangeKm,
        BigDecimal consumptionKwhPer100km,
        List<String> connectorTypes,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) {
}
