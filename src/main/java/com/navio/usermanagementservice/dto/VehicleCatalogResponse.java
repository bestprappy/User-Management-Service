package com.navio.usermanagementservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A dated specification from the official distributor in the named market. */
public record VehicleCatalogResponse(
        String id, String make, String model, String trim, Short year,
        String market, BigDecimal batteryCapacityKwh, String batteryCapacityBasis,
        BigDecimal rangeKm, String rangeStandard, List<String> connectorTypes,
        BigDecimal maxAcKw, BigDecimal maxDcKw, String imageUrl,
        String sourceUrl, LocalDate verifiedAt
) {
}
